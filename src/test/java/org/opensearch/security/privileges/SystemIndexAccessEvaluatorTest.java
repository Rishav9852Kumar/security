/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.privileges;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.get.MultiGetRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexAbstraction;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.security.auditlog.AuditLog;
import org.opensearch.security.privileges.actionlevel.RoleBasedActionPrivileges;
import org.opensearch.security.resolver.IndexResolverReplacer;
import org.opensearch.security.resolver.IndexResolverReplacer.Resolved;
import org.opensearch.security.securityconf.FlattenedActionGroups;
import org.opensearch.security.securityconf.impl.CType;
import org.opensearch.security.securityconf.impl.SecurityDynamicConfiguration;
import org.opensearch.security.securityconf.impl.v7.RoleV7;
import org.opensearch.security.support.ConfigConstants;
import org.opensearch.security.user.User;
import org.opensearch.security.util.MockIndexMetadataBuilder;
import org.opensearch.tasks.Task;

import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.opensearch.security.support.ConfigConstants.SYSTEM_INDEX_PERMISSION;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SystemIndexAccessEvaluatorTest {

    @Mock
    private AuditLog auditLog;
    @Mock
    private IndexResolverReplacer irr;
    @Mock
    private ActionRequest request;
    @Mock
    private Task task;
    @Mock
    private PrivilegesEvaluatorResponse presponse;
    @Mock
    private Logger log;
    @Mock
    ClusterService cs;
    @Mock
    Metadata metadata;
    @Mock
    ClusterState clusterState;

    private SystemIndexAccessEvaluator evaluator;
    private static final String UNPROTECTED_ACTION = "indices:data/read";
    private static final String PROTECTED_ACTION = "indices:data/write";

    private static final String TEST_SYSTEM_INDEX = ".test_system_index";
    private static final String TEST_INDEX = ".test";
    private static final String SECURITY_INDEX = ConfigConstants.OPENDISTRO_SECURITY_DEFAULT_CONFIG_INDEX;

    static final SortedMap<String, IndexAbstraction> indexMetadata = new TreeMap<>(
        MockIndexMetadataBuilder.indices(TEST_INDEX, TEST_SYSTEM_INDEX, SECURITY_INDEX).build()
    );

    User user;
    IndexNameExpressionResolver indexNameExpressionResolver;
    RoleBasedActionPrivileges actionPrivileges;

    private ThreadContext createThreadContext() {
        return new ThreadContext(Settings.EMPTY);
    }

    protected IndexNameExpressionResolver createIndexNameExpressionResolver(ThreadContext threadContext) {
        return new IndexNameExpressionResolver(threadContext);
    }

    public void setup(
        boolean isSystemIndexEnabled,
        boolean isSystemIndexPermissionsEnabled,
        String index,
        boolean createIndexPatternWithSystemIndexPermission
    ) {
        ThreadContext threadContext = createThreadContext();
        indexNameExpressionResolver = createIndexNameExpressionResolver(threadContext);

        try {
            SecurityDynamicConfiguration<RoleV7> rolesConfig = SecurityDynamicConfiguration.fromMap(
                ImmutableMap.of(
                    "role_a",
                    ImmutableMap.of(
                        "index_permissions",
                        Arrays.asList(
                            ImmutableMap.of(
                                "index_patterns",
                                Arrays.asList(index),
                                "allowed_actions",
                                createIndexPatternWithSystemIndexPermission ? Set.of("*", SYSTEM_INDEX_PERMISSION) : Set.of("*")
                            )
                        ),
                        "cluster_permissions",
                        Arrays.asList("*")
                    )
                ),
                CType.ROLES
            );

            this.actionPrivileges = new RoleBasedActionPrivileges(rolesConfig, FlattenedActionGroups.EMPTY, Settings.EMPTY);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // create a user and associate them with the role
        user = new User("user_a").withSecurityRoles(List.of("role_a"));

        // when trying to resolve Index Names

        evaluator = new SystemIndexAccessEvaluator(
            Settings.builder()
                .put(ConfigConstants.SECURITY_SYSTEM_INDICES_KEY, TEST_SYSTEM_INDEX)
                .put(ConfigConstants.SECURITY_SYSTEM_INDICES_ENABLED_KEY, isSystemIndexEnabled)
                .put(ConfigConstants.SECURITY_SYSTEM_INDICES_PERMISSIONS_ENABLED_KEY, isSystemIndexPermissionsEnabled)
                .build(),
            auditLog,
            irr
        );
        evaluator.log = log;

        when(log.isDebugEnabled()).thenReturn(true);
        when(log.isInfoEnabled()).thenReturn(true);
        when(clusterState.metadata()).thenReturn(metadata);
        when(metadata.getIndicesLookup()).thenReturn(indexMetadata);
    }

    PrivilegesEvaluationContext ctx(String action) {
        return new PrivilegesEvaluationContext(
            user,
            ImmutableSet.of("role_a"),
            action,
            request,
            null,
            null,
            indexNameExpressionResolver,
            () -> clusterState,
            actionPrivileges
        );
    }

    @After
    public void after() {
        verifyNoMoreInteractions(auditLog, irr, request, task, presponse, log);
    }

    @Test
    public void testUnprotectedActionOnRegularIndex_systemIndexDisabled() {
        setup(false, false, TEST_INDEX, false);
        final Resolved resolved = createResolved(TEST_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        verifyNoInteractions(presponse);
        assertThat(response, is(presponse));

    }

    @Test
    public void testUnprotectedActionOnRegularIndex_systemIndexPermissionDisabled() {
        setup(true, false, TEST_INDEX, false);
        final Resolved resolved = createResolved(TEST_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        verifyNoInteractions(presponse);
        assertThat(response, is(presponse));
    }

    @Test
    public void testUnprotectedActionOnRegularIndex_systemIndexPermissionEnabled() {
        setup(true, true, TEST_INDEX, false);
        final Resolved resolved = createResolved(TEST_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        verifyNoInteractions(presponse);
        assertThat(response, is(presponse));
    }

    @Test
    public void testUnprotectedActionOnSystemIndex_systemIndexDisabled() {
        setup(false, false, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        verifyNoInteractions(presponse);
        assertThat(response, is(presponse));
    }

    @Test
    public void testUnprotectedActionOnSystemIndex_systemIndexPermissionDisabled() {
        setup(true, false, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        verifyNoInteractions(presponse);
        assertThat(response, is(presponse));
    }

    @Test
    public void testUnprotectedActionOnSystemIndex_systemIndexPermissionEnabled_WithoutSystemIndexPermission() {
        setup(true, true, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        verify(presponse).markComplete();
        assertThat(response, is(presponse));

        verify(auditLog).logSecurityIndexAttempt(request, UNPROTECTED_ACTION, null);
        verify(log).isInfoEnabled();
        verify(log).info(
            "No {} permission for user roles {} to System Indices {}",
            UNPROTECTED_ACTION,
            user.getSecurityRoles(),
            TEST_SYSTEM_INDEX
        );
    }

    @Test
    public void testUnprotectedActionOnSystemIndex_systemIndexPermissionEnabled_WithSystemIndexPermission() {
        setup(true, true, TEST_SYSTEM_INDEX, true);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        final PrivilegesEvaluatorResponse response = evaluator.evaluate(
            request,
            null,
            UNPROTECTED_ACTION,
            resolved,
            presponse,
            ctx(UNPROTECTED_ACTION),
            actionPrivileges,
            user
        );
        assertThat(response, is(presponse));
        // unprotected action is not allowed on a system index
        assertThat(presponse.allowed, is(false));
    }

    @Test
    public void testDisableCacheOrRealtimeOnSystemIndex_systemIndexDisabled() {
        setup(false, false, TEST_SYSTEM_INDEX, false);

        final SearchRequest searchRequest = mock(SearchRequest.class);
        final MultiGetRequest realtimeRequest = mock(MultiGetRequest.class);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(searchRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(realtimeRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);

        verifyNoInteractions(presponse);
    }

    @Test
    public void testDisableCacheOrRealtimeOnSystemIndex_systemIndexPermissionDisabled() {
        setup(true, false, TEST_SYSTEM_INDEX, false);

        final SearchRequest searchRequest = mock(SearchRequest.class);
        final MultiGetRequest realtimeRequest = mock(MultiGetRequest.class);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(searchRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(realtimeRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);

        verify(searchRequest).requestCache(Boolean.FALSE);
        verify(realtimeRequest).realtime(Boolean.FALSE);

        verify(log, times(2)).isDebugEnabled();
        verify(log).debug("Disable search request cache for this request");
        verify(log).debug("Disable realtime for this request");
    }

    @Test
    public void testDisableCacheOrRealtimeOnSystemIndex_systemIndexPermissionEnabled_withoutSystemIndexPermission() {
        setup(true, true, TEST_SYSTEM_INDEX, false);

        final SearchRequest searchRequest = mock(SearchRequest.class);
        final MultiGetRequest realtimeRequest = mock(MultiGetRequest.class);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(searchRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(realtimeRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);

        verify(searchRequest).requestCache(Boolean.FALSE);
        verify(realtimeRequest).realtime(Boolean.FALSE);

        verify(log, times(2)).isDebugEnabled();
        verify(log).debug("Disable search request cache for this request");
        verify(log).debug("Disable realtime for this request");
        verify(auditLog).logSecurityIndexAttempt(request, UNPROTECTED_ACTION, null);
        verify(auditLog).logSecurityIndexAttempt(searchRequest, UNPROTECTED_ACTION, null);
        verify(auditLog).logSecurityIndexAttempt(realtimeRequest, UNPROTECTED_ACTION, null);
        verify(presponse, times(3)).markComplete();
        verify(log, times(2)).isDebugEnabled();
        verify(log, times(3)).isInfoEnabled();
        verify(log, times(3)).info(
            "No {} permission for user roles {} to System Indices {}",
            UNPROTECTED_ACTION,
            user.getSecurityRoles(),
            TEST_SYSTEM_INDEX
        );
        verify(log).debug("Disable search request cache for this request");
        verify(log).debug("Disable realtime for this request");
    }

    @Test
    public void testDisableCacheOrRealtimeOnSystemIndex_systemIndexPermissionEnabled_withSystemIndexPermission() {
        setup(true, true, TEST_SYSTEM_INDEX, true);

        final SearchRequest searchRequest = mock(SearchRequest.class);
        final MultiGetRequest realtimeRequest = mock(MultiGetRequest.class);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(searchRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);
        evaluator.evaluate(realtimeRequest, null, UNPROTECTED_ACTION, resolved, presponse, ctx(UNPROTECTED_ACTION), actionPrivileges, user);

        verify(searchRequest).requestCache(Boolean.FALSE);
        verify(realtimeRequest).realtime(Boolean.FALSE);

        verify(log, times(2)).isDebugEnabled();
        verify(log).debug("Disable search request cache for this request");
        verify(log).debug("Disable realtime for this request");
    }

    @Test
    public void testProtectedActionLocalAll_systemIndexDisabled() {
        setup(false, false, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = Resolved._LOCAL_ALL;

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();
        verify(log).warn("{} for '_all' indices is not allowed for a regular user", "indices:data/write");
    }

    @Test
    public void testProtectedActionLocalAll_systemIndexPermissionDisabled() {
        setup(true, false, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = Resolved._LOCAL_ALL;

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();
        verify(log).warn("{} for '_all' indices is not allowed for a regular user", PROTECTED_ACTION);
    }

    @Test
    public void testProtectedActionLocalAll_systemIndexPermissionEnabled() {
        setup(true, true, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = Resolved._LOCAL_ALL;

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();
        verify(log).warn("{} for '_all' indices is not allowed for a regular user", PROTECTED_ACTION);
    }

    @Test
    public void testProtectedActionOnRegularIndex_systemIndexDisabled() {
        setup(false, false, TEST_INDEX, false);
        final Resolved resolved = createResolved(TEST_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        assertThat(presponse.allowed, is(false));
    }

    @Test
    public void testProtectedActionOnRegularIndex_systemIndexPermissionDisabled() {
        setup(true, false, TEST_INDEX, false);
        final Resolved resolved = createResolved(TEST_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        assertThat(presponse.allowed, is(false));
    }

    @Test
    public void testProtectedActionOnRegularIndex_systemIndexPermissionEnabled() {
        setup(true, true, TEST_INDEX, false);
        final Resolved resolved = createResolved(TEST_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        assertThat(presponse.allowed, is(false));
    }

    @Test
    public void testProtectedActionOnSystemIndex_systemIndexDisabled() {
        setup(false, false, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        assertThat(presponse.allowed, is(false));
    }

    @Test
    public void testProtectedActionOnSystemIndex_systemIndexPermissionDisabled() {
        setup(true, false, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();
        verify(log).warn("{} for '{}' index is not allowed for a regular user", PROTECTED_ACTION, TEST_SYSTEM_INDEX);
    }

    @Test
    public void testProtectedActionOnSystemIndex_systemIndexPermissionEnabled_withoutSystemIndexPermission() {
        setup(true, true, TEST_SYSTEM_INDEX, false);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();
        verify(log).isInfoEnabled();
        verify(log).info(
            "No {} permission for user roles {} to System Indices {}",
            PROTECTED_ACTION,
            user.getSecurityRoles(),
            TEST_SYSTEM_INDEX
        );
    }

    @Test
    public void testProtectedActionOnSystemIndex_systemIndexPermissionEnabled_withSystemIndexPermission() {

        setup(true, true, TEST_SYSTEM_INDEX, true);
        final Resolved resolved = createResolved(TEST_SYSTEM_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        assertThat(presponse.allowed, is(false));
    }

    @Test
    public void testProtectedActionOnProtectedSystemIndex_systemIndexDisabled() {
        setup(false, false, SECURITY_INDEX, false);
        final Resolved resolved = createResolved(SECURITY_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();

        verify(log).warn("{} for '{}' index is not allowed for a regular user", PROTECTED_ACTION, SECURITY_INDEX);
    }

    @Test
    public void testProtectedActionOnProtectedSystemIndex_systemIndexPermissionDisabled() {
        setup(true, false, SECURITY_INDEX, false);
        final Resolved resolved = createResolved(SECURITY_INDEX);

        // Action
        evaluator.evaluate(request, task, PROTECTED_ACTION, resolved, presponse, ctx(PROTECTED_ACTION), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, PROTECTED_ACTION, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();

        verify(log).warn("{} for '{}' index is not allowed for a regular user", PROTECTED_ACTION, SECURITY_INDEX);
    }

    @Test
    public void testUnprotectedActionOnProtectedSystemIndex_systemIndexPermissionEnabled_withoutSystemIndexPermission() {
        testSecurityIndexAccess(UNPROTECTED_ACTION);
    }

    @Test
    public void testUnprotectedActionOnProtectedSystemIndex_systemIndexPermissionEnabled_withSystemIndexPermission() {
        testSecurityIndexAccess(UNPROTECTED_ACTION);
    }

    @Test
    public void testProtectedActionOnProtectedSystemIndex_systemIndexPermissionEnabled_withoutSystemIndexPermission() {
        testSecurityIndexAccess(PROTECTED_ACTION);
    }

    @Test
    public void testProtectedActionOnProtectedSystemIndex_systemIndexPermissionEnabled_withSystemIndexPermission() {
        testSecurityIndexAccess(PROTECTED_ACTION);
    }

    private void testSecurityIndexAccess(String action) {
        setup(true, true, SECURITY_INDEX, true);

        final Resolved resolved = createResolved(SECURITY_INDEX);

        // Action
        evaluator.evaluate(request, task, action, resolved, presponse, ctx(action), actionPrivileges, user);

        verify(auditLog).logSecurityIndexAttempt(request, action, task);
        assertThat(presponse.allowed, is(false));
        verify(presponse).markComplete();

        verify(log).isInfoEnabled();
        verify(log).info(
            "{} not permitted for a regular user {} on protected system indices {}",
            action,
            user.getSecurityRoles(),
            SECURITY_INDEX
        );
    }

    private Resolved createResolved(final String... indexes) {
        return new Resolved(
            ImmutableSet.of(),
            ImmutableSet.copyOf(indexes),
            ImmutableSet.copyOf(indexes),
            ImmutableSet.of(),
            IndicesOptions.STRICT_EXPAND_OPEN
        );
    }
}
