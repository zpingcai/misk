package misk.jdbc

import misk.resources.ResourceLoader
import misk.vitess.Shard
import misk.vitess.target
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.create.table.CreateTable
import wisp.logging.getLogger
import java.sql.ResultSet
import java.util.regex.Pattern

internal class DeclarativeSchemaMigrator(
  private val resourceLoader: ResourceLoader,
  private val dataSourceService: DataSourceService,
  private val connector: DataSourceConnector,
  private val skeemaWrapper: SkeemaWrapper,
) : BaseSchemaMigrator(resourceLoader, dataSourceService, connector) {
  private val logger = getLogger<DeclarativeSchemaMigrator>()

  override fun validateMigrationFile(migrationFile: MigrationFile): Boolean {
    return !Pattern.compile(connector.config().migrations_resources_regex)
      .matcher(migrationFile.filename).matches()
  }

  override fun applyAll(author: String): MigrationStatus {
    var appliedMigrations = false
    for (shard in shards.get()) {
      val migrationFiles = getMigrationFiles(shard.keyspace)
      if (migrationFiles.isNotEmpty()) {
        skeemaWrapper.applyMigrations(migrationFiles)
        appliedMigrations = true
      }
    }
    return if (appliedMigrations) MigrationStatus.Success else MigrationStatus.Empty
  }

  override fun requireAll(): MigrationStatus {
    for (shard in shards.get()) {
      val expectedTables = availableMigrations(shard)
      val actualTables = appliedMigrations(shard)
      val excludedTables = excludedTables()
      compareMigrations(expectedTables, actualTables, excludedTables)
    }

    return MigrationStatus.Success
  }

  private fun excludedTables(): Set<String> {
    return connector.config().declarative_schema_config?.excluded_tables?.toSet() ?: emptySet()
  }

  /**
   * Compare expected tables from migration files to actual database tables
   */
  private fun compareMigrations(
    expectedTables: Map<String, Set<String>>,
    actualTables: Map<String, Set<String>>,
    excludedTables: Set<String>
  ) {
    logger.info { "Comparing expected tables $expectedTables to actual tables $actualTables in the database" }
    for ((expectedTable, expectedColumns) in expectedTables) {
      if (excludedTables.contains(expectedTable)) {
        continue
      }
      // Check if table exists in the database
      val actualColumns = actualTables[expectedTable]
        ?: throw IllegalStateException("Error: Table $expectedTable missing in the database.")

      // Compare columns in the migration file to actual columns in the database
      for (columnName in expectedColumns) {
        if (!actualColumns.contains(columnName)) {
          throw IllegalStateException("Error: Column $columnName for table $expectedTable is missing in the database.")
        }
        }
      }
    }

  /**
   * Helper function to parse migration files and extract expected tables and columns
   */
  private fun availableMigrations(shard: Shard): Map<String, Set<String>> {
    // Read the .sql Files
    val migrationFiles = getMigrationFiles(shard.keyspace)
    val tables = mutableMapOf<String, Set<String>>()

    for (file in migrationFiles) {
      val fileContent = resourceLoader.utf8(file.filename).toString()

      try {
        // Parse the file content
        val statement = CCJSqlParserUtil.parse(fileContent)

        // Check if the parsed statement is a CREATE TABLE statement
        if (statement is CreateTable) {
          val tableName = statement.table.name.lowercase().removeSurrounding("`")
          val columns = mutableSetOf<String>()

          // Iterate over columns to extract names and data types
          statement.columnDefinitions?.forEach { columnDefinition ->
            columns.add(columnDefinition.columnName.lowercase().removeSurrounding("`"))
          }

          tables[tableName] = columns
        } else {
          throw IllegalStateException("No valid CREATE TABLE statement found in ${file.filename}")
        }
      } catch (e: Exception) {
        throw IllegalStateException("Failed to parse SQL in ${file.filename}")
      }
    }

    return tables
  }

  /**
   * Helper function to parse database and extract actual tables and columns
   */
  private fun appliedMigrations(shard: Shard): Map<String, Set<String>> {
    // Store actual database tables and their columns
    val actualTables = mutableMapOf<String, Set<String>>()
    val dbName = connector.config().database

    dataSourceService.dataSource.connection.use {
      it.target(shard) { conn ->
        // Use DatabaseMetaData to get all table names
        val metaData = conn.metaData
        val tablesResultSet: ResultSet =
          metaData.getTables(dbName, null, "%", arrayOf("TABLE"))

        // Iterate through all tables in the database
        while (tablesResultSet.next()) {
          val tableName = tablesResultSet.getString("TABLE_NAME").lowercase()

          // For each table, get column names and types
          val actualColumns = mutableSetOf<String>()
          val columnsResultSet: ResultSet =
            metaData.getColumns(dbName, null, tableName, "%")
          while (columnsResultSet.next()) {
            actualColumns.add(columnsResultSet.getString("COLUMN_NAME").lowercase())
          }

          // Close resources for this table
          columnsResultSet.close()
          actualTables[tableName] = actualColumns
        }

        tablesResultSet.close()
      }
    }

    return actualTables
  }
}
