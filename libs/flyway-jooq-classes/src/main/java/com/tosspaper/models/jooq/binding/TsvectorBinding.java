package com.tosspaper.models.jooq.binding;

import org.jooq.Binding;
import org.jooq.BindingGetResultSetContext;
import org.jooq.BindingGetSQLInputContext;
import org.jooq.BindingGetStatementContext;
import org.jooq.BindingRegisterContext;
import org.jooq.BindingSQLContext;
import org.jooq.BindingSetSQLOutputContext;
import org.jooq.BindingSetStatementContext;
import org.jooq.Converter;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

/**
 * Custom jOOQ binding for PostgreSQL tsvector columns.
 * Maps tsvector DB type to Java String for full-text search support.
 */
public class TsvectorBinding implements Binding<Object, String> {

    @Override
    public Converter<Object, String> converter() {
        return new Converter<>() {
            @Override
            public String from(Object databaseObject) {
                return databaseObject == null ? null : databaseObject.toString();
            }

            @Override
            public Object to(String userObject) {
                return userObject;
            }

            @Override
            public Class<Object> fromType() {
                return Object.class;
            }

            @Override
            public Class<String> toType() {
                return String.class;
            }
        };
    }

    @Override
    public void sql(BindingSQLContext<String> ctx) throws SQLException {
        if (ctx.render().paramType() == ParamType.INLINED) {
            ctx.render().visit(DSL.inline(ctx.convert(converter()).value()));
        } else {
            ctx.render().sql(ctx.variable()).sql("::tsvector");
        }
    }

    @Override
    public void register(BindingRegisterContext<String> ctx) throws SQLException {
        ctx.statement().registerOutParameter(ctx.index(), Types.VARCHAR);
    }

    @Override
    public void set(BindingSetStatementContext<String> ctx) throws SQLException {
        ctx.statement().setString(ctx.index(), ctx.convert(converter()).value());
    }

    @Override
    public void set(BindingSetSQLOutputContext<String> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void get(BindingGetResultSetContext<String> ctx) throws SQLException {
        ctx.convert(converter()).value(ctx.resultSet().getString(ctx.index()));
    }

    @Override
    public void get(BindingGetStatementContext<String> ctx) throws SQLException {
        ctx.convert(converter()).value(ctx.statement().getString(ctx.index()));
    }

    @Override
    public void get(BindingGetSQLInputContext<String> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }
}
