/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.grpc.server.security;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.context.ApplicationContext;
import org.springframework.security.access.hierarchicalroles.NullRoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.SecurityConfigurerAdapter;
import org.springframework.security.core.Authentication;
import org.springframework.util.Assert;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.function.SingletonSupplier;

public class RequestMapperConfigurer
		extends SecurityConfigurerAdapter<AuthenticationServerInterceptor, GrpcSecurity> {

	private List<AuthorizedCall> authorizedCalls = new ArrayList<>();

	private final Supplier<RoleHierarchy> roleHierarchy;

	public RequestMapperConfigurer(ApplicationContext context) {
		this.roleHierarchy = SingletonSupplier.of(() -> (context.getBeanNamesForType(RoleHierarchy.class).length > 0)
				? context.getBean(RoleHierarchy.class)
				: new NullRoleHierarchy());
	}

	@Override
	public void configure(GrpcSecurity builder) throws Exception {
		builder.authorizationManager(new RequestMapperAuthorizationManager(this.authorizedCalls));
	}

	public AuthorizedCall allRequests() {
		AuthorizedCall call = new AuthorizedCall(CallMatcher.ALL);
		this.authorizedCalls.add(call);
		return call;
	}

	public AuthorizedCall methods(String... patterns) {
		AuthorizedCall call = new AuthorizedCall(new MethodCallMatcher(patterns));
		this.authorizedCalls.add(call);
		return call;
	}

	private static class MethodCallMatcher implements CallMatcher {

		private String[] patterns;

		public MethodCallMatcher(String... patterns) {
			this.patterns = patterns;
		}

		@Override
		public boolean matches(CallContext context) {
			return PatternMatchUtils.simpleMatch(patterns, context.method().getFullMethodName());
		}
	}

	public class AuthorizedCall {

		static final AuthorizationManager<Object> permitAllAuthorizationManager = (a,
				o) -> new AuthorizationDecision(true);

		private CallMatcher matcher;

		private boolean not;

		private AuthorizationManager<Object> authorizationManager;

		public AuthorizedCall(CallMatcher matcher) {
			this.matcher = matcher;
		}

		public AuthorizedCall not() {
			this.not = true;
			return this;
		}

		public RequestMapperConfigurer permitAll() {
			return access(permitAllAuthorizationManager);
		}

		public RequestMapperConfigurer denyAll() {
			return access((a, o) -> new AuthorizationDecision(false));
		}

		public RequestMapperConfigurer hasAuthority(String authority) {
			return access(withRoleHierarchy(AuthorityAuthorizationManager.hasAuthority(authority)));
		}

		public RequestMapperConfigurer hasAnyAuthority(String... authorities) {
			return access(withRoleHierarchy(AuthorityAuthorizationManager.hasAnyAuthority(authorities)));
		}

		public RequestMapperConfigurer access(AuthorizationManager<Object> manager) {
			Assert.notNull(manager, "manager cannot be null");
			this.authorizationManager = (this.not)
					? AuthorizationManagers.not(manager)
					: manager;
			return RequestMapperConfigurer.this;
		}

		private AuthorityAuthorizationManager<Object> withRoleHierarchy(
				AuthorityAuthorizationManager<Object> manager) {
			manager.setRoleHierarchy(RequestMapperConfigurer.this.roleHierarchy.get());
			return manager;
		}

	}

	public static class RequestMapperAuthorizationManager implements AuthorizationManager<CallContext> {

		private final List<AuthorizedCall> authorizedCalls;

		public RequestMapperAuthorizationManager(List<AuthorizedCall> authorizedCalls) {
			this.authorizedCalls = authorizedCalls;
		}

		@SuppressWarnings("deprecation")
		@Override
		public AuthorizationDecision check(Supplier<Authentication> authentication, CallContext context) {
			for (AuthorizedCall authorizedCall : this.authorizedCalls) {
				if (authorizedCall.matcher.matches(context)) {
					return authorizedCall.authorizationManager.check(authentication, context);
				}
			}
			return new AuthorizationDecision(false);
		}

	}

}
