/*
 * Copyright 2020 Dropbox, Inc.
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

package com.arpnetworking.metrics.portal.scheduling;

import com.arpnetworking.commons.serialization.Deserializer;
import com.arpnetworking.commons.serialization.Serializer;

/**
 * A serializer that provides a way to map entity ids to job refs and vice-versa.
 * <p>
 * This is used as somewhat of a crutch for the lack of dynamic props in Akka's
 * classic actor clustering, and would be unnecessary if we later move to typed
 * actors.
 * <p>
 * See <a href=https://stackoverflow.com/a/26524666>this SO post</a> for more details. This is Option A.
 *
 * @author Christian Briones (cbriones at dropbox dot com)
 */
public interface JobRefSerializer extends Serializer<JobRef<?>>, Deserializer<JobRef<?>> {}
