/*
 * Copyright 2024-2025 the original author or authors.
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
package org.springframework.data.jpa.repository.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;
import org.springframework.util.CompositeIterator;

/**
 * Abstraction to encapsulate query expressions and render a query.
 * <p>
 * Query rendering consists of multiple building blocks:
 * <ul>
 * <li>{@link QueryTokens.SimpleQueryToken tokens} and {@link QueryTokens.ExpressionToken expression tokens}</li>
 * <li>{@link QueryRenderer compositions} such as a composition of multiple tokens.</li>
 * <li>{@link QueryRenderer expressions} that are individual parts such as {@code SELECT} or {@code ORDER BY …}</li>
 * <li>{@link QueryRenderer inline expressions} such as composition of tokens and expressions such as function calls
 * with parenthesis {@code SOME_FUNCTION(ARGS)}</li>
 * </ul>
 *
 * @author Mark Paluch
 * @author Christoph Strobl
 */
abstract class QueryRenderer implements QueryTokenStream {

	/**
	 * Creates a QueryRenderer from a collection of {@link QueryToken}.
	 */
	static QueryRenderer from(Collection<? extends QueryToken> tokens) {
		List<QueryToken> tokensToUse = new ArrayList<>(Math.max(tokens.size(), 32));
		tokensToUse.addAll(tokens);
		return new TokenRenderer(tokensToUse);
	}

	/**
	 * Creates a QueryRenderer from a {@link QueryTokenStream}.
	 */
	static QueryRenderer from(QueryTokenStream tokens) {

		if (tokens instanceof QueryRendererBuilder builder) {
			tokens = builder.current;
		}

		if (tokens instanceof QueryRenderer renderer) {
			return renderer;
		}

		return new QueryStreamRenderer(tokens);
	}

	/**
	 * Creates a new empty {@link QueryRenderer}.
	 */
	public static QueryRenderer empty() {
		return EmptyQueryRenderer.INSTANCE;
	}

	/**
	 * Creates a new {@link QueryRendererBuilder}.
	 */
	static QueryRendererBuilder builder() {
		return new QueryRendererBuilder();
	}

	/**
	 * @return the rendered query.
	 */
	abstract String render();

	/**
	 * @return the rendered query.
	 */
	static String render(Iterable<QueryToken> tokenStream) {

		if (tokenStream instanceof QueryRendererBuilder qrb) {
			tokenStream = qrb.current;
		}

		if (tokenStream instanceof QueryRenderer qr) {
			return qr.render();
		}

		StringBuilder results = null;
		boolean previousExpression = false;

		Iterator<QueryToken> iterator = tokenStream.iterator();
		while (iterator.hasNext()) {
			QueryToken token = iterator.next();

			if (results == null) {
				if (iterator.hasNext()) {
					results = new StringBuilder();
				} else {
					return token.value();
				}
			}

			if (previousExpression) {
				if (!results.isEmpty() && results.charAt(results.length() - 1) != ' ') {
					results.append(' ');
				}
			}

			previousExpression = token.isExpression();
			results.append(token.value());
		}

		return results != null ? results.toString() : "";
	}

	/**
	 * Append a {@link QueryRenderer} to create a composed renderer.
	 */
	QueryRenderer append(QueryTokenStream tokens) {

		if (tokens instanceof QueryRendererBuilder builder) {
			tokens = builder.current;
		}

		if (tokens instanceof QueryRenderer qr) {

			if (isEmpty()) {
				return qr;
			}

			return CompositeRenderer.combine(this, qr);
		}

		if (isEmpty()) {
			return QueryRenderer.from(tokens);
		}

		return CompositeRenderer.combine(this, QueryRenderer.from(tokens));
	}

	@Override
	public String toString() {
		return render();
	}

	public static QueryRenderer ofExpression(QueryTokenStream tokenStream) {

		if (tokenStream instanceof ExpressionRenderer er) {
			return er;
		}

		if (tokenStream instanceof QueryRendererBuilder builder) {
			tokenStream = builder.current;
		}

		if (tokenStream.isEmpty()) {
			return EmptyQueryRenderer.INSTANCE;
		}

		if (!(tokenStream instanceof QueryRenderer)) {
			tokenStream = QueryRenderer.from(tokenStream);
		}

		if (tokenStream.isExpression()) {
			return (QueryRenderer) tokenStream;
		}

		return new ExpressionRenderer((QueryRenderer) tokenStream);
	}

	public static QueryRenderer inline(QueryTokenStream tokenStream) {

		Assert.notNull(tokenStream, "QueryTokenStream must not be null!");

		if (tokenStream instanceof InlineRenderer ilr) {
			return ilr;
		}

		if (tokenStream instanceof QueryRendererBuilder builder) {
			tokenStream = builder.current;
		}

		if (tokenStream.isEmpty()) {
			return EmptyQueryRenderer.INSTANCE;
		}

		if (!(tokenStream instanceof QueryRenderer)) {
			tokenStream = QueryRenderer.from(tokenStream);
		}

		return new InlineRenderer((QueryRenderer) tokenStream);
	}

	/**
	 * Composed renderer consisting of one or more QueryRenderers.
	 */
	static class CompositeRenderer extends QueryRenderer {

		private final List<QueryRenderer> nested;

		static CompositeRenderer combine(QueryRenderer root, QueryRenderer nested) {

			List<QueryRenderer> queryRenderers = new ArrayList<>(32);
			queryRenderers.add(root);
			queryRenderers.add(nested);

			return new CompositeRenderer(queryRenderers);
		}

		private CompositeRenderer(List<QueryRenderer> nested) {
			this.nested = nested;
		}

		@Override
		String render() {

			StringBuilder builder = new StringBuilder(64);
			String lastAppended = null;

			boolean lastExpression = false;
			for (QueryRenderer queryRenderer : nested) {

				if (lastAppended != null && (lastExpression || queryRenderer.isExpression()) && !builder.isEmpty()
						&& (!lastAppended.endsWith(" ") && !lastAppended.endsWith("("))) {
					builder.append(' ');
				}

				lastAppended = queryRenderer.render();
				builder.append(lastAppended);
				lastExpression = queryRenderer.isExpression();
			}

			return builder.toString();
		}

		/**
		 * Append a {@link QueryRenderer} to create a composed renderer.
		 */
		QueryRenderer append(QueryTokenStream tokens) {

			if (tokens instanceof QueryRendererBuilder builder) {
				tokens = builder.current;
			}

			if (tokens instanceof QueryRenderer qr) {

				if (isEmpty()) {
					return this;
				}

				if (qr.isEmpty()) {
					return qr;
				}

				if (tokens instanceof CompositeRenderer cr) {
					this.nested.addAll(cr.nested);

					return this;
				}

			}

			return super.append(tokens);
		}

		@Override
		public @Nullable QueryToken getLast() {

			for (int i = nested.size() - 1; i > -1; i--) {

				QueryRenderer renderer = nested.get(i);

				if (!renderer.isEmpty()) {
					return renderer.getLast();
				}
			}

			return null;
		}

		@Override
		public Iterator<QueryToken> iterator() {

			CompositeIterator<QueryToken> iterator = new CompositeIterator<>();
			for (QueryTokenStream stream : nested) {
				iterator.add(stream.iterator());
			}
			return iterator;
		}

		@Override
		public boolean isEmpty() {

			for (QueryRenderer renderer : nested) {
				if (!renderer.isEmpty()) {
					return false;
				}
			}

			return true;
		}

		@Override
		public int size() {

			int size = 0;

			for (QueryTokenStream stream : nested) {
				size += stream.size();
			}
			return size;
		}

		@Override
		public boolean isExpression() {
			return !nested.isEmpty() && nested.get(nested.size() - 1).isExpression();
		}

	}

	/**
	 * Renderer using {@link QueryTokens.SimpleQueryToken}.
	 */
	static class TokenRenderer extends QueryRenderer {

		private final List<QueryToken> tokens;

		TokenRenderer(List<QueryToken> tokens) {
			this.tokens = tokens;
		}

		@Override
		String render() {
			return render(tokens);
		}

		@Override
		public Stream<QueryToken> stream() {
			return tokens.stream();
		}

		@Override
		public Iterator<QueryToken> iterator() {
			return tokens.iterator();
		}

		@Override
		public List<QueryToken> toList() {
			return tokens;
		}

		@Override
		public @Nullable QueryToken getFirst() {
			return tokens.isEmpty() ? null : tokens.get(0);
		}

		@Override
		public @Nullable QueryToken getLast() {
			return tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
		}

		@Override
		public int size() {
			return tokens.size();
		}

		@Override
		public boolean isEmpty() {
			return tokens.isEmpty();
		}

		@Override
		public boolean isExpression() {
			return !tokens.isEmpty() && getRequiredLast().isExpression();
		}

	}

	static class QueryStreamRenderer extends QueryRenderer {

		private final QueryTokenStream tokens;

		public QueryStreamRenderer(QueryTokenStream tokens) {
			this.tokens = tokens;
		}

		@Override
		String render() {
			return render(tokens);
		}

		@Override
		public Iterator<QueryToken> iterator() {
			return tokens.iterator();
		}

		@Override
		public @Nullable QueryToken getFirst() {
			return tokens.getFirst();
		}

		@Override
		public @Nullable QueryToken getLast() {
			return tokens.getLast();
		}

		@Override
		public int size() {
			return tokens.size();
		}

		@Override
		public boolean isEmpty() {
			return tokens.isEmpty();
		}

		@Override
		public boolean isExpression() {
			return !tokens.isEmpty() && tokens.getRequiredLast().isExpression();
		}
	}

	/**
	 * Builder for {@link QueryRenderer}.
	 */
	static class QueryRendererBuilder implements QueryTokenStream {

		protected QueryRenderer current = QueryRenderer.empty();

		/**
		 * Append a collection of {@link QueryToken}s.
		 *
		 * @param tokens
		 * @return {@code this} builder.
		 */
		QueryRendererBuilder append(List<? extends QueryToken> tokens) {
			return append(QueryRenderer.from(tokens));
		}

		/**
		 * Append a QueryRenderer.
		 *
		 * @param stream
		 * @return {@code this} builder.
		 */
		QueryRendererBuilder append(QueryTokenStream stream) {

			if (stream.isEmpty()) {
				return this;
			}

			current = current.append(stream);

			return this;
		}

		/**
		 * Append a QueryRenderer inline.
		 *
		 * @param stream
		 * @return {@code this} builder.
		 */
		QueryRendererBuilder appendInline(QueryTokenStream stream) {

			if (stream.isEmpty()) {
				return this;
			}

			current = current.append(QueryRenderer.inline(stream));

			return this;
		}

		/**
		 * Append a QueryRendererBuilder as expression.
		 *
		 * @param builder
		 * @return {@code this} builder.
		 */
		QueryRendererBuilder appendExpression(QueryRendererBuilder builder) {
			return appendExpression(builder.current);
		}

		/**
		 * Append a QueryRenderer as expression.
		 *
		 * @param tokens
		 * @return {@code this} builder.
		 */
		QueryRendererBuilder appendExpression(QueryTokenStream tokens) {

			if (tokens.isEmpty()) {
				return this;
			}

			current = current.append(QueryRenderer.ofExpression(tokens));

			return this;
		}

		@Override
		public List<QueryToken> toList() {
			return current.toList();
		}

		@Override
		public Stream<QueryToken> stream() {
			return current.stream();
		}

		@Override
		public @Nullable QueryToken getFirst() {
			return current.getFirst();
		}

		@Override
		public @Nullable QueryToken getLast() {
			return current.getLast();
		}

		@Override
		public boolean isExpression() {
			return current.isExpression();
		}

		@Override
		public boolean isEmpty() {
			return current.isEmpty();
		}

		@Override
		public int size() {
			return current.size();
		}

		@Override
		public Iterator<QueryToken> iterator() {
			return current.iterator();
		}

		@Override
		public String toString() {
			return current.render();
		}

		public QueryRenderer build() {
			return current;
		}

		public QueryRenderer toInline() {
			return new InlineRenderer(current);
		}
	}

	private static class InlineRenderer extends QueryRenderer {

		private final QueryRenderer delegate;

		private InlineRenderer(QueryRenderer delegate) {
			this.delegate = delegate;
		}

		@Override
		String render() {
			return delegate.render();
		}

		@Override
		public Stream<QueryToken> stream() {
			return delegate.stream();
		}

		@Override
		public List<QueryToken> toList() {
			return delegate.toList();
		}

		@Override
		public Iterator<QueryToken> iterator() {
			return delegate.iterator();
		}

		@Override
		public @Nullable QueryToken getFirst() {
			return delegate.getFirst();
		}

		@Override
		public @Nullable QueryToken getLast() {
			return delegate.getLast();
		}

		@Override
		public boolean isEmpty() {
			return delegate.isEmpty();
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean isExpression() {
			return false;
		}
	}

	private static class ExpressionRenderer extends QueryRenderer {

		private final QueryRenderer delegate;

		private ExpressionRenderer(QueryRenderer delegate) {
			this.delegate = delegate;
		}

		@Override
		String render() {
			return delegate.render();
		}

		@Override
		public Stream<QueryToken> stream() {
			return delegate.stream();
		}

		@Override
		public List<QueryToken> toList() {
			return delegate.toList();
		}

		@Override
		public Iterator<QueryToken> iterator() {
			return delegate.iterator();
		}

		@Override
		public @Nullable QueryToken getFirst() {
			return delegate.getFirst();
		}

		@Override
		public @Nullable QueryToken getLast() {
			return delegate.getLast();
		}

		@Override
		public boolean isEmpty() {
			return delegate.isEmpty();
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean isExpression() {
			return true;
		}

	}

	private static class EmptyQueryRenderer extends QueryRenderer {

		public static final QueryRenderer INSTANCE = new EmptyQueryRenderer();

		@Override
		String render() {
			return "";
		}

		@Override
		QueryRenderer append(QueryTokenStream tokens) {

			if (tokens.isEmpty()) {
				return this;
			}

			if (tokens instanceof QueryRenderer qr) {
				return qr;
			}

			return QueryRenderer.from(tokens);
		}

		@Override
		public List<QueryToken> toList() {
			return Collections.emptyList();
		}

		@Override
		public Stream<QueryToken> stream() {
			return Stream.empty();
		}

		@Override
		public Iterator<QueryToken> iterator() {
			return Collections.emptyIterator();
		}

		@Override
		public boolean isEmpty() {
			return true;
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public boolean isExpression() {
			return false;
		}
	}
}
