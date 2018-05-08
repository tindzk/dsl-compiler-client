package com.dslplatform.compiler.client.parameters;

import com.dslplatform.compiler.client.CompileParameter;
import com.dslplatform.compiler.client.Context;
import com.dslplatform.compiler.client.ExitException;

import java.io.File;

public enum SqlPath implements CompileParameter {
	INSTANCE;

	@Override
	public String getAlias() {
		return "sql";
	}

	@Override
	public String getUsage() {
		return "path";
	}

	@Override
	public boolean check(final Context context) throws ExitException {
		return true;
	}

	@Override
	public void run(final Context context) {
	}

	@Override
	public String getShortDescription() {
		return "Where to save SQL migration";
	}

	@Override
	public String getDetailedDescription() {
		return "SQL migration script which contains DDL changes..\n" +
				"When deploying changes to the production, previously created SQL script should be applied.\n" +
				"SQL path can be specified so created/applied SQL scripts can be stored and used later.";
	}
}
