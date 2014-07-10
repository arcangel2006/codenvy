/*******************************************************************************
 * Copyright (c) 2014 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package com.codenvy.client;

import static com.google.common.base.Preconditions.checkNotNull;

import javax.ws.rs.core.Response;

import com.codenvy.client.model.Error;

/**
 * Exception thrown when something is wrong with the REST API.
 * 
 * @author Kevin Pollet
 */
public class CodenvyErrorException extends RuntimeException {
    private static final long serialVersionUID = 7031838814322889179L;

    /**
     * Reads the {@code Response} body and constructs an instance of {@link CodenvyErrorException}.
     * 
     * @param response the rest API {@link Response}.
     * @throws NullPointerException if response parameter is {@code null}.
     */
    static CodenvyErrorException from(Response response) {
        final Error codenvyError = checkNotNull(response).readEntity(Error.class);
        return new CodenvyErrorException(response.getStatus(), codenvyError.message);
    }

    private final int status;

    /**
     * Constructs an instance of {@link CodenvyErrorException}.
     * 
     * @param status the HTTP status code.
     * @param message the error message.
     */
    private CodenvyErrorException(int status, String message) {
        super(message);

        this.status = status;
    }

    /**
     * Returns the HTTP status code.
     * 
     * @return the HTTP status code.
     */
    public int getStatus() {
        return status;
    }
}
