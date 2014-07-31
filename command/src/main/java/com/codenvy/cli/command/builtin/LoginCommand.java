/*******************************************************************************
 * Copyright (c) 2012-2014 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package com.codenvy.cli.command.builtin;

import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Allow to login in the default remote or a given remote
 * @author Florent Benoit
 */
@Command(scope = "codenvy", name = "login", description = "Login into a remote Codenvy system")
public class LoginCommand extends AbsCommand {

    @Option(name = "--remote", description = "Name of the remote codenvy", required = false)
    private String remoteName;

    @Argument(name = "username", description = "username of the remote instance", required = false, multiValued = false, index = 0)
    private String username;

    @Argument(name = "password", description = "password of the remote instance", required = false, multiValued = false, index = 1)
    private String password;


    @Override
    protected Object doExecute() throws Exception {

        init();

        // Is there any available remote ?
        if (!checkifAvailableRemotes()) {
            return null;
        }

        // no username and no password, needs to prompt
        if (username == null) {
            System.out.print("Username:");
            System.out.flush();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(session.getKeyboard(), Charset.defaultCharset()))) {
                username = reader.readLine();
            }
        }
        System.out.println(System.lineSeparator());

        if (password == null) {
            System.out.print("Password for " + username + ":");
            System.out.flush();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(session.getKeyboard(), Charset.defaultCharset()))) {
                password = reader.readLine();
            }
            System.out.println(System.lineSeparator());
        }

        if (getMultiRemoteCodenvy().login(remoteName, username, password)) {
            if (remoteName == null) {
                System.out.println("Login success on default remote '" + getMultiRemoteCodenvy().getDefaultRemoteName() + "'.");
            } else {
                System.out.println("Login success on remote '" + remoteName + "'.");
            }
        } else {
            System.out.println("Login failed: please check the credentials.");
        }

        return null;
    }
}
