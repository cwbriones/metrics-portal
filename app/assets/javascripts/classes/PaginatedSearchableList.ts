/*
 * Copyright 2016 Smartsheet.com
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

import ko = require('knockout');

interface PagerElement { name: string; page: number; disabled: boolean; active: boolean}
interface Pagination { total: number; offset: number}

interface PagerOptions {
    // The title to display above the pager.
    title?: string;
    // Whether or not to display the create entity button in the pager.
    create?: boolean,
    // The text to display on the create entity button.
    createLabel?: string;
    // The URI to link to from the create entity button.
    createUri?: string;
}

abstract class PaginatedSearchableList<T> {
    filteredEntities: KnockoutObservableArray<T> = ko.observableArray<T>();
    searchExpression: KnockoutObservable<string> = ko.observable('');
    searchExpressionThrottled: KnockoutComputed<string> = ko.computed<string>(() => {
        return this.searchExpression();
    }).extend({rateLimit: {timeout: 400, method: "notifyWhenChangesStop"}});
    page: KnockoutObservable<number> = ko.observable(1);
    pagesMax: KnockoutObservable<number> = ko.observable(1);
    private pagerElements: KnockoutComputed<PagerElement[]>;
    private perPage = 24;

    entityName: string;
    options: PagerOptions = {};

    constructor(entityName, options: PagerOptions = {}) {
        var self = this;
        this.searchExpressionThrottled.subscribe(() => {this.page(1); this.query();});

        this.entityName = entityName;
        this.options = {
            title: options.title || (entityName.charAt(0).toUpperCase() + entityName.slice(1) + 's'),
            create: options.create === undefined ? true : options.create,
            createLabel: options.createLabel || `Create new ${entityName}`,
            createUri: options.createUri || `#${entityName}/edit`,
        };

        this.pagerElements = ko.computed(() => {
            var p = this.page();
            var elements: PagerElement[] = [];

            var prevDisabled = p == 1;
            elements.push({name: "Prev", page: p - 1, disabled: prevDisabled, active: false});
            if (p > 3) {
                elements.push({name: "1", page: 1, disabled: false, active: false});
            }

            if (p > 4) {
                elements.push({name: "...", page: 0, disabled: true, active: false});
            }

            for (var i = p - 2; i <= p + 2; i++) {
                if (i >= 1 && i <= this.pagesMax()) {
                    var active = i == p;
                    elements.push({name: String(i), page: i, disabled: false, active: active});
                }
            }

            if (p <= this.pagesMax() - 4) {
                elements.push({name: "...", page: 0, disabled: true, active: false});
            }

            if (p <= this.pagesMax() - 3) {
                var last = this.pagesMax();
                elements.push({name: String(last), page: last, disabled: false, active: false});
            }

            var nextDisabled = p == this.pagesMax();
            elements.push({name: "Next", page: p + 1, disabled: nextDisabled, active: false});

            return elements;
        }, self);

        this.gotoPage = (element: PagerElement) => {
            if (element.disabled || element.page == this.page()) {
                return;
            }
            this.page(element.page);
            this.query();
        }
    }

    private gotoPage: (element: PagerElement) => void;

    abstract fetchData(query: any, callback: (data: T[], pagination: Pagination) => void): void;

    public query() {
        var query = this.createQuery();
        this.amendQuery(query);

        this.fetchData(query, (data, pagination) => {
            this.filteredEntities.removeAll();
            this.filteredEntities(data);
            var pages = Math.floor(pagination.total / this.perPage) + 1;
            // If total is a multiple of the page size
            if (pagination.total % this.perPage == 0) {
                pages--;
            }
            this.pagesMax(pages);
            this.page(Math.floor(pagination.offset / this.perPage) + 1);
            if (this.filteredEntities().length == 0 && this.page() > 1) {
                this.page(this.page() - 1);
                this.query();
            }

        });
    }

    amendQuery(query: any): void { }

    createQuery() : any {
        var expression = this.searchExpressionThrottled();
        var offset = (this.page() - 1) * this.perPage;
        var query: any = {limit: this.perPage, offset: offset};
        if (expression && expression != "") {
            query.contains = expression;
        }
        return query;
    }
}

export = PaginatedSearchableList;
