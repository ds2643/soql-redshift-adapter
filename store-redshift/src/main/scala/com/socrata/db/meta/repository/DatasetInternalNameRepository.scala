package com.socrata.db.meta.repository

import com.socrata.db.meta.entity.{Dataset, DatasetInternalName}
import io.quarkus.hibernate.orm.panache.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

import java.util.Optional

@ApplicationScoped
class DatasetInternalNameRepository extends PanacheRepository[DatasetInternalName] {
  def findByName(name: String): Optional[Dataset] = find("name", name).project(classOf[Dataset]).singleResultOptional()

}
