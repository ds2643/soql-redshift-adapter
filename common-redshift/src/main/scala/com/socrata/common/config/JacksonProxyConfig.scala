package com.socrata.common.config

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.fasterxml.jackson.databind.{
  JsonNode,
  ObjectMapper,
  PropertyNamingStrategies
}
import com.socrata.common.config.JacksonProxyConfigBuilder.merge
import com.socrata.common.config.JsonNodeBackedJacksonInvocationHandler.{
  innerGenericClass,
  kebab
}
import java.lang.reflect.{InvocationHandler, Method, ParameterizedType, Proxy}
import java.util.Properties
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

// An object containing helper methods to recursively merge json nodes and environment properties
object JacksonProxyConfigBuilder {

  // method to merge all environment source properties into one
  // starting from the start property and adding all key-value pairs from each environment source
  // it works by folding over the sequence of environment sources, at each step adding all properties of the current environment source to the accumulator properties
  def merge(envSources: Seq[EnvSource], start: Properties): Properties = {
    envSources.foldLeft(start)(
      (acc, item) => { // https://github.com/scala/bug/issues/10418
        item
          .read()
          .forEach((k, v) =>
            acc.put(k, v)
          ) // adding all key-value pairs from the environment source to the accumulator
        acc // returning the accumulator for the next step
      }
    )
  }

  // method to merge all configuration sources into one json node
  // starting from the start json node and merging each configuration source read into it
  def merge(configSources: Seq[ConfigSource], start: JsonNode): JsonNode =
    configSources.foldLeft(start)((acc, item) => merge(acc, item.read()))

  // method to merge two JsonNodes by adding all field values from the updateNode into the mainNode
  def merge(mainNode: JsonNode, updateNode: JsonNode): JsonNode = {
    val fieldNames =
      updateNode.fieldNames // getting field names of the update node
    // iterating over all field names and adding the values from updateNode to mainNode
    while (fieldNames.hasNext) {
      // get the field name
      val fieldName = fieldNames.next
      // get the field value from the main node. if the field is already present, its value will be replaced with the value in updateNode
      val jsonNode = mainNode.get(fieldName)
      if (
        jsonNode != null && jsonNode.isObject
      ) // if the field is an object itself, merge the sub-fields
        merge(jsonNode, updateNode.get(fieldName))
      else {
        // if not, simply replace the old field value with the new one from updateNode
        mainNode match {
          case node: ObjectNode =>
            val value = updateNode.get(fieldName)
            node.replace(fieldName, value)
          case _ =>
        }
      }
    }
    // return the merged mainNode
    mainNode
  }
}

trait ConfigSource {
  // reads the configuration source and returns its content as a json node
  // to be implemented by specific configuration sources
  def read(): JsonNode
}

trait EnvSource {
  // reads the environment source and returns its content as properties
  // to be implemented by specific environment sources
  def read(): Properties
}

trait ConfigProvider {
  // creates a proxy for a configuration interface located at a certain path
  // the configuration interface will be loaded with values from that path in the source
  def proxy[T](path: String, clazz: Class[T]): T

  // creates a proxy for a configuration interface and loads it with the root configuration source
  def proxy[T](clazz: Class[T]): T

  // returns a sub provider providing access to a subset of the configurations (a chunk of them) from a certain path
  // once you chunk, further chunking or sourcing is not possible
  def chunk(path: String): ConfigProvider
}

trait ConfigBuilder {
  // allows building the configuration provider with multiple configuration sources
  // returns a hybrid of config builder and config provider
  def withSources(
      configSources: ConfigSource*
  ): ConfigBuilder with ConfigProvider

  // allows building the configuration provider with multiple environment sources
  // returns a hybrid of config builder and config provider
  def withEnvs(configEnvs: EnvSource*): ConfigBuilder with ConfigProvider
}

// a concrete implementation of the ConfigBuilder, based on Jackson and a supplied object mapper
case class JacksonProxyConfigBuilder(private val objectMapper: ObjectMapper)
    extends ConfigBuilder {
  // adding configuration sources
  // create a config provider based on the merged configuration source data
  override def withSources(
      configSources: ConfigSource*
  ): JacksonProxyConfigProvider =
    JacksonProxyConfigProvider(
      merge(
        configSources,
        objectMapper.createObjectNode().asInstanceOf[JsonNode]
      ),
      objectMapper,
      new Properties()
    )

  // adding environment sources
  // create a config provider based on the merged environment data
  override def withEnvs(configEnvs: EnvSource*): JacksonProxyConfigProvider =
    JacksonProxyConfigProvider(
      objectMapper.createObjectNode().asInstanceOf[JsonNode],
      objectMapper,
      merge(configEnvs, new Properties())
    )
}

object JsonNodeBackedJacksonInvocationHandler {
  // helper to get the generic type argument of a method's return type, if it is parameterized
  def innerGenericClass(method: Method): Class[_] =
    Class.forName(
      method.getGenericReturnType
        .asInstanceOf[ParameterizedType]
        .getActualTypeArguments
        .head
        .getTypeName
    )

  // helper to convert camelCase property names to kebab-case
  def kebab(in: String): String = {
    PropertyNamingStrategies.KebabCaseStrategy.INSTANCE.translate(
      in
    ) // transforming the input string into kebab-case
  }
}

// a concrete InvocationHandler implementation, which uses Jackson's ObjectMapper to convert and marshal data on the fly
case class JsonNodeBackedJacksonInvocationHandler(
    data: JsonNode, // the current json node serving as the data source
    objectMapper: ObjectMapper, // the Jackson ObjectMapper for data conversion/marshalling
    env: Properties // the environment properties, serving as a source for property resolution
) extends InvocationHandler {
  // string template pattern for property resolution, e.g. ${something}
  private val pattern = "\\$\\{([^}]+)}".r

  // the main method of the InvocationHandler, handling invocations on the proxy
  // it uses the method name to find a corresponding value in the data json, and then tries to convert this value to the required return type of the method
  def invoke(
      proxy: scala.AnyRef, // the instance on which the method is invoked, the proxy itself
      method: Method, // the actual method invoked
      args: Array[AnyRef] // the arguments passed to the method
  ): AnyRef = {
    val out =
      method.getName match {
        // special handling for toString method, returns the string representation of the data json
        case "toString" =>
          data.toString
        // for all other methods, it searches for corresponding value in the data json
        case methodName =>
          data.findValue(kebab(methodName)) match {
            // if no corresponding value is found, a NotImplementedError is thrown
            case null =>
              throw new NotImplementedError(
                s"Unsupported method name '$methodName', implement it above (probably)!"
              )
            // different handling based on the data type
            case value: ObjectNode =>
              handleObject(value, method) // for Json object nodes
            case value: ArrayNode =>
              handleArray(
                value.elements().asScala,
                innerGenericClass(method)
              ) // for Json array nodes
            case value =>
              doConvert(
                value.toString,
                method.getReturnType
              ) // and for all other value types, the json is directly converted to the return type
          }
      }
    out.asInstanceOf[AnyRef] // cast output to AnyRef, because it's the return type of the InvocationHandler's invoke method
  }

  // handling json object nodes
  // depending on whether the return type of the method is an Option or another complex type, the object node is either directly converted to the return type, or a proxy is created for it
  private def handleObject(data: JsonNode, method: Method): Any = {
    method.getReturnType.getName match {
      case "scala.Option" =>
        Try(doConvert(data.toString, innerGenericClass(method))) match {
          case Failure(_) =>
            Try(doProxy(data, innerGenericClass(method))) match {
              // Proxy creation failed, return None
              case Failure(_) => None
              case Success(value) =>
                Some(value) // if it succeeded, wrap the result with Some
            }
          case Success(value) =>
            Some(
              value
            ) // if conversion was successful, return the result wrapped with Some
        }
      case _ =>
        Try(doConvert(data.toString, method.getReturnType)) match {
          case Failure(_) =>
            doProxy(
              data,
              method.getReturnType
            ) // if conversion failed, try with proxy creation
          case Success(value) => value // if convertible, return converted value
        }
    }
  }

  // handling json array nodes
  // it tries first to convert each item, if that fails, it then tries creating a proxy for it
  private def handleArray[T](
      data: Iterator[JsonNode],
      target: Class[T]
  ): List[T] = {
    data.toList.map {
      case item: ObjectNode =>
        Try(doConvert(item.toString, target)) match {
          case Failure(_) =>
            doProxy(
              item,
              target
            ) // if conversion failed, attempt with proxy creation
          case Success(value) => value // if convertible, return converted value
        }
      case item =>
        doConvert(item.toString, target) // if not, directly convert value
    }
  }

  // Private method to create a new proxy for the provided JsonNode
  private def doProxy[T](data: JsonNode, target: Class[T]): T = {
    JacksonProxyConfigProvider(data, objectMapper, env).proxy(
      target
    ) // creating a proxy for the given class type
  }

  // Private method to convert a Json value to a target class
  private def doConvert[T](value: String, returning: Class[T]): T = {
    val replaced = fillHoles(
      value
    ) // Fill any ${propertyName} in the value with the property value from environment, if available
    objectMapper.readValue(
      replaced,
      returning
    ) // Using Jackson's ObjectMapper to convert the value to the required type
  }

  // Method to replace the placeholders (referencing environment properties) in the value string with the corresponding values from the environment
  private def fillHoles(value: String): String = {
    // For each match of the pattern in the value, the whole matched string (including the surrounding ${ and }) is replaced
    // with the corresponding property value from the environment, or an empty string if the property does not exist
    pattern.findAllIn(value).matchData.foldLeft(value) { (str, matchData) =>
      val target = matchData.group(1).trim
      str.replaceAllLiterally(
        matchData.matched,
        env.getProperty(
          target,
          System.getProperty(target, System.getenv(target))
        ) // Attempt to resolve the property from the environment, or from the system properties or environment variables
      )
    }
  }

}

// A specific concrete implementation of the ConfigProvider and ConfigBuilder traits, using a JsonNode as the main data holder, and a Jackson ObjectMapper for data conversion/marshalling
case class JacksonProxyConfigProvider(
    private val data: JsonNode, // The root JsonNode, containing the configuration data
    private val objectMapper: ObjectMapper, // a Jackson ObjectMapper for data conversion/marshalling
    private val env: Properties // The set of properties, containing any environment data
) extends ConfigProvider
    with ConfigBuilder {

  // method to merge additional configuration sources into the existing one
  override def withSources(
      configSources: ConfigSource*
  ): JacksonProxyConfigProvider =
    JacksonProxyConfigProvider(
      merge(
        configSources,
        data
      ), // merge the existing data with the newly provided sources
      objectMapper,
      env
    )

  // method to merge additional environment sources into the existing ones
  override def withEnvs(
      configEnvs: EnvSource*
  ): ConfigBuilder with ConfigProvider =
    JacksonProxyConfigProvider(
      data,
      objectMapper,
      merge(
        configEnvs,
        env
      ) // merge the existing environment with the newly provided ones
    )

  // extracts a subset of configuration (a chunk) from the data at a certain path
  def chunk(path: String): JacksonProxyConfigProvider =
    JacksonProxyConfigProvider(data.findValue(path), objectMapper, env)

  // creates a proxy for a configuration interface located at a certain path
  def proxy[T](path: String, clazz: Class[T]): T =
    proxy(clazz, data.findValue(path))

  // An auxiliary proxy creation method, coupled to a specific JsonNode data
  private def proxy[T](clazz: Class[T], newData: JsonNode): T = {
    Proxy
      .newProxyInstance(
        clazz.getClassLoader, // Use the classloader of the target class
        Array(clazz), // The proxy will be implementing our target interface
        JsonNodeBackedJacksonInvocationHandler(newData, objectMapper, env)
      )
      .asInstanceOf[T] // forcing cast to our target interface
  }

  // creates a proxy for a configuration interface using the root node as the data
  def proxy[T](clazz: Class[T]): T = {
    proxy(clazz, data)
  }
}
