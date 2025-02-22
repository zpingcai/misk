package misk.jdbc

import com.google.inject.util.Providers
import misk.MiskTestingServiceModule
import misk.config.MiskConfig
import misk.environment.DeploymentModule
import misk.inject.KAbstractModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import wisp.config.Config
import wisp.deployment.TESTING
import jakarta.inject.Inject
import jakarta.inject.Qualifier

@MiskTest(startService = true)
internal class TruncateTablesServiceTest {
  @MiskTestModule
  val module = TestModule()

  @Inject @TestDatasource lateinit var transacter: Transacter
  @Inject @TestDatasource lateinit var dataSourceProvider: DataSourceService

  @BeforeEach
  internal fun setUp() {
    // Manually truncate because we don't use TruncateTablesService to test itself!
    transacter.transaction { connection ->
      val statement = connection.createStatement()
      statement.execute("DELETE FROM movies")
    }
  }

  @Test
  fun truncateUserTables() {
    assertThat(rowCount("schema_version")).isGreaterThan(0)
    assertThat(rowCount("movies")).isEqualTo(0)

    // Insert some data.
    transacter.transaction { connection ->
      connection.createStatement().use { statement ->
        statement.execute("INSERT INTO movies (name) VALUES ('Star Wars')")
        statement.execute("INSERT INTO movies (name) VALUES ('Jurassic Park')")
      }
    }
    assertThat(rowCount("schema_version")).isGreaterThan(0)
    assertThat(rowCount("movies")).isGreaterThan(0)

    // Start up TruncateTablesService. The inserted data should be truncated.
    val service =
      TruncateTablesService(TestDatasource::class, dataSourceProvider, Providers.of(transacter))
    service.startAsync()
    service.awaitRunning()
    assertThat(rowCount("schema_version")).isGreaterThan(0)
    assertThat(rowCount("movies")).isEqualTo(0)
  }

  @Test
  fun startUpStatements() {
    val service = TruncateTablesService(
      TestDatasource::class,
      dataSourceProvider,
      Providers.of(transacter),
      startUpStatements = listOf("INSERT INTO movies (name) VALUES ('Star Wars')")
    )

    assertThat(rowCount("movies")).isEqualTo(0)
    service.startAsync()
    service.awaitRunning()
    assertThat(rowCount("movies")).isGreaterThan(0)
  }

  @Test
  fun shutDownStatements() {
    val service = TruncateTablesService(
      TestDatasource::class,
      dataSourceProvider,
      Providers.of(transacter),
      shutDownStatements = listOf("INSERT INTO movies (name) VALUES ('Star Wars')")
    )

    service.startAsync()
    service.awaitRunning()

    assertThat(rowCount("movies")).isEqualTo(0)
    service.stopAsync()
    service.awaitTerminated()
    assertThat(rowCount("movies")).isGreaterThan(0)
  }

  private fun rowCount(table: String): Int {
    return transacter.transaction { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM $table").map {
          it.getInt(1)
        }[0]
      }
    }
  }

  class TestModule : KAbstractModule() {
    override fun configure() {
      install(DeploymentModule(TESTING))
      install(MiskTestingServiceModule())

      val config = MiskConfig.load<TestConfig>("test_truncatetables_app", TESTING)
      install(JdbcModule(TestDatasource::class, config.data_source))
    }
  }

  data class TestConfig(val data_source: DataSourceConfig) : Config

  @Qualifier
  @Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
  annotation class TestDatasource
}
