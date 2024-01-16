package com.socrata.common.converter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.socrata.common.converter.PropertiesDeserializer.flattenObj

import java.util
import java.util.Properties
import scala.collection.JavaConverters._

// Object PropertiesDeserializer contains utility function working with Properties
object PropertiesDeserializer {

  // This function converts nested Map into flattened Map with keys concatenated
  def flattenObj(
      o: Map[String, Object], // Input Multilevel Map
      keyAcc: String =
        "", // Accumulator for keys found on the way through nested Maps
      delimiter: String = "." // Delimiter to separate concatenated keys
  ): Map[String, String] = {

    // Use flatMap to iterate each element and apply flatten
    o.flatMap {

      // For each element, unpack it's key and value
      case (key, value) => {

        // Construct newKey by concatenating keyAcc and the current key
        val newKey: String =
          if (keyAcc.isEmpty) key else keyAcc + delimiter + key

        // Pattern match the value to recursively process nested objects and flatten them
        value match {

          // If value is another Map, recursively flatten it.
          case map: Map[_, _] => {

            // Recursive call to process nested Map
            flattenObj(
              map.asInstanceOf[
                Map[String, Object]
              ], // Cast to Map[String, Object]
              newKey, // Pass newKey as accumulator
              delimiter // Pass delimiter
            )
          }

          // If single value, just bridge it into new single-element Map for consistency
          case value => Map(newKey -> value.toString)
        }

      }
    }
  }
}

// Class for deserializing JSON input into Java Properties
class PropertiesDeserializer
    extends StdDeserializer[Properties](classOf[Properties]) {

  // Method override for custom deserialization of JSON into Properties
  override def deserialize(
      p: JsonParser, // JsonParser from Jackson library
      ctxt: DeserializationContext // Provides access to contextual information during deserialization
  ): Properties = {

    // Obtain a deserializer that matches the type of HashMap in JsonParser
    val mapDeserializer = findDeserializer(
      ctxt, // Pass ctxt
      ctxt
        .getTypeFactory()
        .constructMapType(
          classOf[util.HashMap[String, Object]], // Expected Result type
          classOf[String], // Expected key type
          classOf[Object] // Expected value type
        ),
      null // TypeDeserializer, set to null as not required for Map
    );

    // Deserialize the input JSON into a HashMap
    val rawValue: util.HashMap[String, Object] = mapDeserializer
      .deserialize(p, ctxt) // Deserialize input
      .asInstanceOf[util.HashMap[String, Object]] // Cast outcome as HashMap

    // Check if deserialized value is null, if yes then return null immediately
    if (rawValue == null) {
      return null
    }

    // Create a new Properties object to store the final result
    val mappedValue = new Properties()

    // Convert Java's HashMap into Map from scala, then apply flattenObj to flatten it
    val flattened = flattenObj(rawValue.asScala.toMap)

    // Add each flattened element into mappedValue
    flattened.foreach { case (key, value) =>
      mappedValue.put(key, value)
    }

    // Return final result as Properties
    mappedValue
  }
}
