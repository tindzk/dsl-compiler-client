package com.dslplatform.compiler.client.cmdline.processor;

import java.io.File;
import java.io.IOException;

import com.dslplatform.compiler.client.api.Actions;
import com.dslplatform.compiler.client.api.params.Arguments;
import com.dslplatform.compiler.client.api.params.DSL;
import com.dslplatform.compiler.client.api.params.Language;
import com.dslplatform.compiler.client.api.params.PackageName;
import com.dslplatform.compiler.client.api.params.ProjectID;
import com.dslplatform.compiler.client.api.processors.ParseAndDiffProcessor;
import com.dslplatform.compiler.client.api.processors.UpdateProcessor;
import com.dslplatform.compiler.client.api.processors.UpdateUnsafeProcessor;
import com.dslplatform.compiler.client.cmdline.params.AuthProvider;
import com.dslplatform.compiler.client.io.Login;
import com.dslplatform.compiler.client.io.Output;
import com.dslplatform.compiler.client.io.Prompt;
import com.dslplatform.compiler.client.io.Logger;

public class UpdateHandler extends BaseHandler {
    private final Logger logger;
    private final Prompt prompt;
    private final Output output;
    private final Login login;
    private final Actions actions;

    public UpdateHandler(
            final Logger logger,
            final Prompt prompt,
            final Output output,
            final Login login,
            final Actions actions) {
        super(logger, prompt);
        this.logger = logger;
        this.prompt = prompt;
        this.output = output;
        this.login = login;
        this.actions = actions;
    }

    public void apply(final Arguments arguments) throws IOException {
        final DSL dsl = arguments.getDsl();

        // sanity check to force early failure
        final ProjectID projectID = arguments.getProjectID();

        // sanity check to force early failure
        arguments.getOutputPath();

        final AuthProvider authProvider = new AuthProvider(logger, prompt, login, arguments);

        if (!arguments.isSkipDiff()) {
            final ParseAndDiffProcessor pdp = actions.parseAndDiff(authProvider.getAuth(), dsl, projectID);

            if (pdp.isAuthorized()) {
                authProvider.setToken(pdp.getAuthorization());
            }
            else if (authProvider.isToken()) {
                authProvider.removeToken();
                apply(arguments);
                return;
            }

            for(final String diff : pdp.getDiffs()) {
                output.println(diff);
            }

            output.println(pdp.getResponse());

            if (!pdp.isSuccessful()) {
                final char retry =
                        prompt.readCharacter("Reload [Y]es/[N]o: ", "ynYN");

                if (retry != 'Y' && retry != 'y') {
                    output.println("Update cancelled.");
                    return;
                }
                else {
                    apply(arguments);
                    return;
                }
            }

            if (!pdp.isAutoConfirm()) {
                final char confirmation =
                        prompt.readCharacter("Confirm [Y]es/[N]o: ", "ynYN");

                if (confirmation != 'Y' && confirmation != 'y') {
                    output.println("Update cancelled.");
                    return;
                }
            }
        }

        final Language[] languages = arguments.getLanguages();
        final PackageName packageName = arguments.getPackageName();

        final UpdateProcessor up = actions.update(
                authProvider.getAuth(), dsl, projectID, packageName, languages);

        final UpdateUnsafeProcessor uqp;

        if (up.needsConfirmation()) {
            output.println(up.getConfirmationMessage());

            final char confirmation =
                    prompt.readCharacter("Confirm [Y]es/[N]o: ", "ynYN");

            if (confirmation != 'Y' && confirmation != 'y') {
                output.println("Update cancelled.");
                return;
            }

            final UpdateUnsafeProcessor uup = actions.updateUnsafe(
                    authProvider.getAuth(), dsl, projectID, packageName, languages);

            uqp = uup;
        }
        else {
            uqp = up;
        }

        for (final String message : uqp.getMessages()) {
            output.println(message);
        }

        updateFiles(arguments, uqp.getFileBodies());
    }
}