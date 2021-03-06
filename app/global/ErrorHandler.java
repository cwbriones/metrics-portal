/*
 * Copyright 2014 Groupon.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package global;

import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import play.http.HttpErrorHandler;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Error handler for the application.
 *
 * @author Brandon Arp (brandon dot arp at inscopemetrics dot io)
 */
public class ErrorHandler implements HttpErrorHandler {
    @Override
    public CompletionStage<Result> onClientError(final Http.RequestHeader requestHeader, final int status, final String message) {
        LOGGER.warn()
                .setMessage("error on client request")
                .addData("request", requestHeader)
                .addData("reason", message)
                .addData("httpStatus", status)
                .log();
        return CompletableFuture.completedFuture(Results.status(status));
    }

    @Override
    public CompletionStage<Result> onServerError(final Http.RequestHeader requestHeader, final Throwable throwable) {
        LOGGER.error()
                .setMessage("error processing request")
                .addData("request", requestHeader)
                .addData("method", requestHeader.method())
                .addData("path", requestHeader.path())
                .setThrowable(throwable)
                .log();
        return CompletableFuture.completedFuture(Results.status(500));
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorHandler.class);
}
