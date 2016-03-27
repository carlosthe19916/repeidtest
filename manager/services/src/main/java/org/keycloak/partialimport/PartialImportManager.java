/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.partialimport;

import java.util.ArrayList;
import java.util.List;
import javax.ws.rs.core.Response;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.PartialImportRepresentation;
import org.keycloak.services.resources.admin.AdminEventBuilder;

/**
 * This class manages the PartialImport handlers.
 *
 * @author Stan Silvert ssilvert@redhat.com (C) 2016 Red Hat Inc.
 */
public class PartialImportManager {
    private final List<PartialImport> partialImports = new ArrayList<>();

    private final PartialImportRepresentation rep;
    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminEventBuilder adminEvent;

    public PartialImportManager(PartialImportRepresentation rep, KeycloakSession session,
                                RealmModel realm, AdminEventBuilder adminEvent) {
        this.rep = rep;
        this.session = session;
        this.realm = realm;
        this.adminEvent = adminEvent;

        // Do not change the order of these!!!
        partialImports.add(new ClientsPartialImport());
        partialImports.add(new RolesPartialImport());
        partialImports.add(new IdentityProvidersPartialImport());
        partialImports.add(new UsersPartialImport());
    }

    public Response saveResources() {

        PartialImportResults results = new PartialImportResults();

        for (PartialImport partialImport : partialImports) {
            try {
                partialImport.prepare(rep, realm, session);
            } catch (ErrorResponseException error) {
                if (session.getTransaction().isActive()) session.getTransaction().setRollbackOnly();
                return error.getResponse();
            }
        }

        for (PartialImport partialImport : partialImports) {
            try {
                partialImport.removeOverwrites(realm, session);
                results.addAllResults(partialImport.doImport(rep, realm, session));
            } catch (ErrorResponseException error) {
                if (session.getTransaction().isActive()) session.getTransaction().setRollbackOnly();
                return error.getResponse();
            }
        }

        for (PartialImportResult result : results.getResults()) {
            switch (result.getAction()) {
                case ADDED : addedEvent(result); break;
                case OVERWRITTEN: overwrittenEvent(result); break;
            }
        }

        if (session.getTransaction().isActive()) {
            session.getTransaction().commit();
        }

        return Response.ok(results).build();
    }

    private void addedEvent(PartialImportResult result) {
        adminEvent.operation(OperationType.CREATE)
                  .resourcePath(result.getResourceType().getPath(), result.getId())
                  .representation(result.getRepresentation())
                  .success();
    };

    private void overwrittenEvent(PartialImportResult result) {
        adminEvent.operation(OperationType.UPDATE)
                  .resourcePath(result.getResourceType().getPath(), result.getId())
                  .representation(result.getRepresentation())
                  .success();
    }

}