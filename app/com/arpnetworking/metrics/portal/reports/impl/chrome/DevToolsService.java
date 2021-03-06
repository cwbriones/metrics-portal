/*
 * Copyright 2019 Dropbox, Inc.
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
package com.arpnetworking.metrics.portal.reports.impl.chrome;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A relatively minimal interface for a Chrome tab's dev tools.
 *
 * @author Spencer Pearson (spencerpearson at dropbox dot com)
 */
public interface DevToolsService {
    /**
     * Evaluates some JavaScript in the tab.
     *
     * @param js A JavaScript expression. (If you need multiple statements, wrap them in an
     *   <a href="https://developer.mozilla.org/en-US/docs/Glossary/IIFE">IIFE</a>.)
     * @return The result of the evaluation. (e.g. a String, a Double, a-- I don't know about arrays/objects.)
     * @throws IllegalStateException If the service is closed.
     */
    CompletableFuture<Object> evaluate(String js);

    /**
     * Creates a PDF capturing how the page currently displays.
     *
     * @param pageWidth How wide the PDF's pages should be, in inches.
     * @param pageHeight How tall the PDF's pages should be, in inches.
     * @return Raw bytes of the PDF, suitable for e.g. writing to a .pdf file.
     * @throws IllegalStateException If the service is closed.
     */
    CompletableFuture<byte[]> printToPdf(double pageWidth, double pageHeight);

    /**
     * Checks whether a given URL is legal to pass to {@link #navigate}.
     *
     * @param url The URL to check.
     * @return False iff the URL will raise a {@link IllegalArgumentException} when passed to {@link #navigate}.
     */
    boolean isNavigationAllowed(String url);

    /**
     * Forces the tab to navigate to a new URL.
     *
     * @param url The URL to navigate to.
     * @return A {@link CompletableFuture} that completes when the page has loaded.
     * @throws IllegalArgumentException If the given URL is illegal, as given by {@link #isNavigationAllowed}.
     * @throws IllegalStateException If the service is closed.
     */
    CompletableFuture<Void> navigate(String url);

    /**
     * Closes the dev tools. After close() is called, any further interaction is illegal
     * (except further calls to close(), which are no-ops).
     * @return A {@link CompletableFuture} that completes when the devtools and their associated tab have closed.
     */
    CompletableFuture<Void> close();

    /**
     * Create a {@link CompletableFuture} that completes when {@code eventName} fires, or immediately if the event has already fired.
     *
     * (Context: event handlers can only be registered on a page after it's finished loading.
     * This introduces the possibility that the event you want to listen for will have <i>already happened</i> by the time
     * you attach a listener for it. It's impossible in the general case to tell whether this has happened,
     * but if you can reliably tell whether the event has fired, then you can ensure that you either catch the event
     * <i>or</i> notice that it's already fired.
     *
     * @param eventName The name of the JavaScript event to listen for.
     * @param ready Determine whether the event has already fired.
     * @return A {@link CompletableFuture} that completes when the event has fired (or immediately, if {@code ready} returns true).
     * @throws IllegalStateException If the service is closed.
     */
    CompletableFuture<Void> nowOrOnEvent(String eventName, Supplier<Boolean> ready);
}
