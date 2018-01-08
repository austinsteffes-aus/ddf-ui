/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
/*global require, window */
/*jslint nomen:false, -W064 */
require([
    'jquery',
    'application',
    'properties',
    'store',
    'component/singletons/user-instance',
    'js/MediaQueries',
    'js/Theming',
    'js/SystemUsage',
    'component/singletons/session-auto-renew',
    'js/SessionTimeout'
], function($, app, properties, store, user) {

    var workspaces = store.get('workspaces');

    function attemptToStart() {
        if (workspaces.fetched && user.fetched){
            app.App.start({});
        } else if (!user.fetched){
            user.once('sync', function() {
                attemptToStart();
            });
        } else {
            workspaces.once('sync', function() {
                attemptToStart();
            });
        }
    }

    //$(window).trigger('resize');
    $(window.document).ready(function() {
        window.document.title = properties.branding + ' ' + properties.product;
    });
    attemptToStart();
});