package app

import com.augustnagro.magnum.*

object Migrations:

  def createSitesTable(using DbCon) =
    sql"""
          CREATE TABLE IF NOT EXISTS sites (
            id SERIAL PRIMARY KEY,
            subdomain TEXT NOT NULL UNIQUE,
            delete_token VARCHAR(255) NOT NULL
          )""".update.run()

  def runMigrations(using conn: DbCon, conf: Config) =
    scribe.info("creating sites table")
    createSitesTable
    scribe.info("done")
