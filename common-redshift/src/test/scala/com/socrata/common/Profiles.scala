package com.socrata.common

import io.quarkus.test.junit.QuarkusTestProfile
import scala.collection.JavaConverters._

import java.util

object Profiles {
  class Integration extends QuarkusTestProfile {
    override def getConfigProfile: String = "integration"

    override def tags(): util.Set[String] = Set("integration").asJava
  }
  class Unit extends QuarkusTestProfile {
    override def getConfigProfile: String = "test"

    override def tags(): util.Set[String] = Set("unit").asJava
  }
}
