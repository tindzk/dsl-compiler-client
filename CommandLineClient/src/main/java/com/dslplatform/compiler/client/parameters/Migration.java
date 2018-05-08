package com.dslplatform.compiler.client.parameters;

import com.dslplatform.compiler.client.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public enum Migration implements CompileParameter {
	INSTANCE;

	@Override
	public String getAlias() {
		return "migration";
	}

	@Override
	public String getUsage() {
		return null;
	}

	private final static String DESCRIPTION_START = "/*MIGRATION_DESCRIPTION";
	private final static String DESCRIPTION_END = "MIGRATION_DESCRIPTION*/";

	private static final String POSTGRES_MIGRATION_FILE_NAME = "postgres_migration_file";
	private static final String ORACLE_MIGRATION_FILE_NAME = "oracle_migration_file";

	public static File getPostgresMigrationFile(final Context context) {
		return context.load(POSTGRES_MIGRATION_FILE_NAME);
	}

	public static File getOracleMigrationFile(final Context context) {
		return context.load(ORACLE_MIGRATION_FILE_NAME);
	}

	public static String[] extractDescriptions(final String sql) throws ExitException {
		final int start = sql.indexOf(DESCRIPTION_START);
		final int end = sql.indexOf(DESCRIPTION_END);
		if (end > start) {
			return sql.substring(start + DESCRIPTION_START.length(), end).split("\n");
		}
		return new String[0];
	}

	@Override
	public boolean check(final Context context) {
		if (context.contains(INSTANCE)) {
			if (!context.contains(PostgresConnection.INSTANCE)
					&& !context.contains(OracleConnection.INSTANCE)) {
				context.error("Connection string is required to create a migration script.\n"
						+ "Neither Oracle or Postgres connection string found");
				return false;
			}
			if (context.contains(SqlPath.INSTANCE)) {
				final String value = context.get(SqlPath.INSTANCE);
				if (value == null || value.length() == 0) {
					return true;
				}
			}
		}
		return true;
	}

	@Override
	public void run(final Context context) throws ExitException {
		if (context.contains(Migration.INSTANCE)) {
			final String value = context.get(SqlPath.INSTANCE);
			final String fallbackFileName =
					"sql-migration-" + (new Date().getTime()) + ".sql";

			final File path;
			if (!context.contains(SqlPath.INSTANCE) || value == null || value.length() == 0) {
				path = new File(TempPath.getTempProjectPath(context), fallbackFileName);
			} else if (new File(value).isDirectory()) {
				path = new File(value, fallbackFileName);
			} else {
				path = new File(value);
			}

			if (context.contains(PostgresConnection.INSTANCE)) {
				final DatabaseInfo dbInfo = PostgresConnection.getDatabaseDslAndVersion(context);
				createMigration(context, path, dbInfo, POSTGRES_MIGRATION_FILE_NAME);
			} else if (context.contains(OracleConnection.INSTANCE)) {
				final DatabaseInfo dbInfo = OracleConnection.getDatabaseDslAndVersion(context);
				createMigration(context, path, dbInfo, ORACLE_MIGRATION_FILE_NAME);
			}
		}
	}

	private static void createMigration(
			final Context context,
			final File output,
			final DatabaseInfo dbInfo,
			final String databaseType) throws ExitException {
		final List<File> currentDslFiles = DslPath.getDslPaths(context);
		context.show("Creating SQL migration for " + dbInfo.database + "...");

		final String target = dbInfo.database.toLowerCase() + dbInfo.dbVersion;

		final Optional<File> previousDslFile;
		final Optional<String> previousCompilerVersion;
		if (dbInfo.dsl == null || dbInfo.dsl.isEmpty()) {
			previousDslFile = Optional.empty();
			previousCompilerVersion = Optional.empty();
		} else {
			previousDslFile = Optional.of(new File(TempPath.getTempProjectPath(context), "old.dsl"));
			previousCompilerVersion = Optional.of(dbInfo.compilerVersion);

			final StringBuilder oldDsl = new StringBuilder();
			for (final String v : dbInfo.dsl.values()) {
				oldDsl.append(v);
			}
			try {
				Utils.saveFile(context, previousDslFile.get(), oldDsl.toString());
			} catch (IOException ex) {
				context.error("Unable to save old DSL version for comparison.");
				throw new ExitException();
			}
		}

		final Either<String> migration = DslCompiler.migration(
				context, target, previousDslFile, previousCompilerVersion, currentDslFiles);
		if (!migration.isSuccess()) {
			context.error("Error creating SQL migration:");
			context.error(migration.whyNot());
			throw new ExitException();
		}
		final String script = migration.get();

		if (script.length() > 0) {
			try {
				Utils.saveFile(context, output, script);
			} catch (IOException e) {
				context.error("Error saving migration script to " + output.getAbsolutePath());
				context.error(e);
				throw new ExitException();
			}
			context.show("Migration saved to " + output.getAbsolutePath());
			final String[] descriptions = extractDescriptions(script);
			for (int i = 1; i < descriptions.length; i++) {
				context.log(descriptions[i]);
			}
			context.cache(databaseType, output);
		} else {
			context.show("No database changes detected.");
			context.cache(databaseType, new File("empty.sql"));
		}
	}

	@Override
	public String getShortDescription() {
		return "Create SQL migration from previous DSL to the current one";
	}

	@Override
	public String getDetailedDescription() {
		return "DSL Platform will compare previously applied DSL with the current one and provide a migration SQL script.\n" +
				"Developer can inspect migration (although it contains a lot of boilerplate due to dependency graph rebuild),\n" +
				"to check if the requested migration matches what he had in mind.\n" +
				"Every migration contains description of the important changes to the database.\n" +
				"\n" +
				"Postgres migrations are transactional due to Transactional DDL Postgres feature.\n" +
				"\n" +
				"While for most migrations ownership of the database is sufficient, some require superuser access (Enum changes, strange primary keys, ...).";
	}
}
