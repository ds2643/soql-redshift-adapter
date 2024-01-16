# Testing
Common is [configured](pom.xml) with the maven jar plugin, this will create a test-sources jar.
Server and Store both depend on this for their test phase. For example, used in sharing the [com.socrata.common.Profiles](common-redshift/src/test/scala/com/socrata/common/Profiles.scala) accross all test sources.
```xml
<plugin>
<groupId>org.apache.maven.plugins</groupId>
<artifactId>maven-jar-plugin</artifactId>
<version>3.3.0</version>
<executions>
  <execution>
    <goals>
      <goal>test-jar</goal>
    </goals>
  </execution>
</executions>
</plugin>
```

To register a class to a specific profile, annotate the test with:
```java
@TestProfile(classOf[Profiles.Integration])
@QuarkusTest
```
Note the [com.socrata.common.Profiles](common-redshift/src/test/scala/com/socrata/common/Profiles.scala) which currently has:
* Unit
* Integration

These profiles can be expanded upon.
JUnit is authoritative on which profile and test to run.
You should target a test via tagging and have JUnit use the configured profile associated with the tag. 

Each profile can override various things, most importantly for us the profile/tags:
```java
class Unit extends QuarkusTestProfile {
    override def getConfigProfile: String = "test"

    override def tags(): util.Set[String] = Set("unit").asJava
  }
```

You can then use junit to selectively execute tests via tags:
```shell
mvn test -Dquarkus.test.profile.tags=unit,integration -DskipTests=false
```

The above configuration will instruct JUnit to run all tests tagged with `unit` or `integration`.
The tests will be grouped by the profiles (`test` and `integration` in this case) and will execute sequentially.
The environment will be torn down and stood up upon switching profiles.

The default profile for testing is `test`, annotating a test with `@TestProfile(classOf[Profiles.Unit])` will have the same affect as leaving it unannotated, however you miss out on the opportunity of being able to target the test via tagging.
