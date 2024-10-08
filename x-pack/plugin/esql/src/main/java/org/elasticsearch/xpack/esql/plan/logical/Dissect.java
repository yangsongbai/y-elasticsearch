/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.dissect.DissectParser;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.core.plan.logical.UnaryPlan;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Dissect extends RegexExtract {
    private final Parser parser;

    public record Parser(String pattern, String appendSeparator, DissectParser parser) {

        public List<Attribute> keyAttributes(Source src) {
            List<Attribute> keys = new ArrayList<>();
            for (var x : parser.outputKeys()) {
                if (x.isEmpty() == false) {
                    keys.add(new ReferenceAttribute(src, x, DataType.KEYWORD));
                }
            }

            return keys;
        }

        // Override hashCode and equals since the parser is considered equal if its pattern and
        // appendSeparator are equal ( and DissectParser uses reference equality )
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Parser that = (Parser) other;
            return Objects.equals(this.pattern, that.pattern) && Objects.equals(this.appendSeparator, that.appendSeparator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern, appendSeparator);
        }
    }

    public Dissect(Source source, LogicalPlan child, Expression input, Parser parser, List<Attribute> extracted) {
        super(source, child, input, extracted);
        this.parser = parser;
    }

    @Override
    public UnaryPlan replaceChild(LogicalPlan newChild) {
        return new Dissect(source(), newChild, input, parser, extractedFields);
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, Dissect::new, child(), input, parser, extractedFields);
    }

    @Override
    public Dissect withGeneratedNames(List<String> newNames) {
        return new Dissect(source(), child(), input, parser, renameExtractedFields(newNames));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (super.equals(o) == false) return false;
        Dissect dissect = (Dissect) o;
        return Objects.equals(parser, dissect.parser);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), parser);
    }

    public Parser parser() {
        return parser;
    }
}
