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

class GraphSpec {
    service: string;
    metric: string;
    statistic: string;
    points: any;
    lines: any;
    bars: any;

    constructor(service: string, metric: string, statistic: string, points: any, lines: any, bars: any) {
        this.service = service;
        this.metric = metric;
        this.statistic = statistic;
        this.points = points;
        this.lines = lines;
        this.bars = bars;
    }
}

export = GraphSpec;
