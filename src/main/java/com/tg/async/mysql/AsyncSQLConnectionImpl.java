package com.tg.async.mysql;

import com.github.mauricio.async.db.Connection;
import com.github.mauricio.async.db.QueryResult;
import com.github.mauricio.async.db.ResultSet;
import com.github.mauricio.async.db.RowData;
import com.tg.async.mysql.pool.ConnectionPool;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import scala.concurrent.ExecutionContext;
import scala.runtime.AbstractFunction1;

import java.util.ArrayList;
import java.util.List;

public class AsyncSQLConnectionImpl implements SQLConnection {

    private final ExecutionContext executionContext;
    private volatile boolean inTransaction = false;
    private boolean inAutoCommit = true;

    private final Connection connection;
    private final ConnectionPool pool;

    public AsyncSQLConnectionImpl(Connection connection, ConnectionPool pool, ExecutionContext executionContext) {
        this.connection = connection;
        this.pool = pool;
        this.executionContext = executionContext;
    }

    @Override
    public SQLConnection call(String sql, Handler<AsyncResult<ResultSet>> resultHandler) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public SQLConnection callWithParams(String sql, JsonArray params, JsonArray outputs, Handler<AsyncResult<ResultSet>> resultHandler) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public SQLConnection setAutoCommit(boolean autoCommit, Handler<AsyncResult<Void>> handler) {
        Future<Void> fut;

        synchronized (this) {
            if (inTransaction && autoCommit) {
                inTransaction = false;
                fut = ScalaUtils.scalaToVertxVoid(connection.sendQuery("COMMIT"), executionContext);
            } else {
                fut = Future.succeededFuture();
            }
            inAutoCommit = autoCommit;
        }

        fut.setHandler(handler);
        return this;

    }

    @Override
    public SQLConnection execute(String sql, Handler<AsyncResult<Void>> handler) {
        beginTransactionIfNeeded(v -> {
            final scala.concurrent.Future<QueryResult> future = connection.sendQuery(sql);
            future.onComplete(ScalaUtils.toFunction1(ar -> {
                if (ar.succeeded()) {
                    handler.handle(Future.succeededFuture());
                } else {
                    handler.handle(Future.failedFuture(ar.cause()));
                }
            }), executionContext);
        });

        return this;
    }

    @Override
    public SQLConnection query(String sql, Handler<AsyncResult<ResultSet>> handler) {
        beginTransactionIfNeeded(v -> {
            final Future<QueryResult> future = ScalaUtils.scalaToVertx(connection.sendQuery(sql), executionContext);
            future.setHandler(handleAsyncQueryResultToResultSet(handler));
        });

        return this;
    }

    @Override
    public SQLConnection queryWithParams(String sql, JsonArray params, Handler<AsyncResult<ResultSet>> handler) {
        beginTransactionIfNeeded(v -> {
            final scala.concurrent.Future<QueryResult> future = connection.sendPreparedStatement(sql,
                    ScalaUtils.toScalaList(params.getList()));
            future.onComplete(ScalaUtils.toFunction1(handleAsyncQueryResultToResultSet(handler)), executionContext);
        });

        return this;
    }


    @Override
    public SQLConnection update(String sql, Handler<AsyncResult<ResultSet>> handler) {
        beginTransactionIfNeeded(v -> {
            final scala.concurrent.Future<QueryResult> future = connection.sendQuery(sql);
            future.onComplete(ScalaUtils.toFunction1(handleAsyncQueryResultToResultSet(handler)), executionContext);
        });

        return this;
    }

    @Override
    public SQLConnection updateWithParams(String sql, JsonArray params, Handler<AsyncResult<ResultSet>> handler) {
        beginTransactionIfNeeded(v -> {
            final scala.concurrent.Future<QueryResult> future = connection.sendPreparedStatement(sql,
                    ScalaUtils.toScalaList(params.getList()));
            future.onComplete(ScalaUtils.toFunction1(handleAsyncUpdateResultToResultSet(handler)), executionContext);
        });

        return this;
    }

    @Override
    public synchronized void close(Handler<AsyncResult<Void>> handler) {
        inAutoCommit = true;
        if (inTransaction) {
            inTransaction = false;
            Future<QueryResult> future = ScalaUtils.scalaToVertx(connection.sendQuery("COMMIT"), executionContext);
            future.setHandler((v) -> {
                pool.returnObject(connection);
                handler.handle(Future.succeededFuture());
            });
        } else {
            pool.returnObject(connection);
            handler.handle(Future.succeededFuture());
        }
    }

    @Override
    public void close() {
        close((ar) -> {
            // Do nothing by default.
        });
    }

    @Override
    public SQLConnection commit(Handler<AsyncResult<Void>> handler) {
        return endAndStartTransaction("COMMIT", handler);
    }

    @Override
    public SQLConnection rollback(Handler<AsyncResult<Void>> handler) {
        return endAndStartTransaction("ROLLBACK", handler);
    }

    @Override
    public SQLConnection setTransactionIsolation(TransactionIsolation transactionIsolation, Handler<AsyncResult<Void>> handler) {
        String sql;
        switch (transactionIsolation) {
            case READ_UNCOMMITTED:
                sql = "SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED";
                break;
            case REPEATABLE_READ:
                sql = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ";
                break;
            case READ_COMMITTED:
                sql = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";
                break;
            case SERIALIZABLE:
                sql = "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE";
                break;
            case NONE:
            default:
                sql = null;
                break;
        }

        if (sql == null) {
            handler.handle(Future.succeededFuture());
            return this;
        }

        return execute(sql, handler);
    }

    @Override
    public SQLConnection getTransactionIsolation(Handler<AsyncResult<TransactionIsolation>> handler) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public SQLConnection batch(List<String> sqlStatements, Handler<AsyncResult<List<Integer>>> handler) {
        // This should be simple in postgres, since it is just append the query separator after each query and send as a big
        // sql statement, however it does not seem to work on mysql
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public SQLConnection batchWithParams(String sqlStatement, List<JsonArray> args, Handler<AsyncResult<List<Integer>>> handler) {
        // This should be simple in postgres, since it is just append the query separator after each query and send as a big
        // sql statement, however it does not seem to work on mysql
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public SQLConnection batchCallableWithParams(String sqlStatement, List<JsonArray> inArgs, List<JsonArray> outArgs, Handler<AsyncResult<List<Integer>>> handler) {
        // No idea how to implement this
        throw new UnsupportedOperationException("Not implemented");
    }

    private SQLConnection endAndStartTransaction(String command, Handler<AsyncResult<Void>> handler) {
        if (inTransaction) {
            inTransaction = false;
            ScalaUtils.scalaToVertx(connection.sendQuery(command), executionContext).setHandler(
                    ar -> {
                        if (ar.failed()) {
                            handler.handle(Future.failedFuture(ar.cause()));
                        } else {
                            ScalaUtils.scalaToVertx(connection.sendQuery("BEGIN"), executionContext).setHandler(
                                    ar2 -> {
                                        if (ar2.failed()) {
                                            handler.handle(Future.failedFuture(ar.cause()));
                                        } else {
                                            inTransaction = true;
                                            handler.handle(Future.succeededFuture());
                                        }
                                    }
                            );
                        }
                    });
        } else {
            handler.handle(Future.failedFuture(
                    new IllegalStateException("Not in transaction currently")));
        }
        return this;
    }

    private synchronized void beginTransactionIfNeeded(Handler<AsyncResult<Void>> action) {
        if (!inAutoCommit && !inTransaction) {
            inTransaction = true;
            ScalaUtils.scalaToVertxVoid(connection.sendQuery("BEGIN"), executionContext)
                    .setHandler(action);
        } else {
            action.handle(Future.succeededFuture());
        }
    }

    private Handler<AsyncResult<QueryResult>> handleAsyncQueryResultToResultSet(Handler<AsyncResult<ResultSet>> handler) {
        return ar -> {
            if (ar.succeeded()) {
                try {
                    handler.handle(Future.succeededFuture(ar.result().rows().get()));
                } catch (Throwable e) {
                    handler.handle(Future.failedFuture(e));
                }
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        };
    }


    private ResultSet queryResultToResultSet(QueryResult qr) {

//        final Option<com.github.mauricio.async.db.ResultSet> rows = qr.rows();
//        if (!rows.isDefined()) {
//            return new ResultSet(Collections.emptyList(), Collections.emptyList(), null);
//        } else {
//            final List<String> names = ScalaUtils.toJavaList(rows.get().columnNames().toList());
//            final List<JsonArray> arrays = rowDataSeqToJsonArray(rows.get());
//            return new ResultSet(names, arrays, null);
//        }


        return qr.rows().get();
    }


    private Handler<AsyncResult<QueryResult>> handleAsyncUpdateResultToResultSet(Handler<AsyncResult<ResultSet>> handler) {
        return ar -> {
            if (ar.succeeded()) {
                try {
                    handler.handle(Future.succeededFuture(queryResultToResultSet(ar.result())));
                } catch (Throwable e) {
                    handler.handle(Future.failedFuture(e));
                }
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        };
    }

    private List<JsonArray> rowDataSeqToJsonArray(com.github.mauricio.async.db.ResultSet set) {
        List<JsonArray> list = new ArrayList<>();
        set.foreach(new AbstractFunction1<RowData, Void>() {
            @Override
            public Void apply(RowData row) {
                list.add(ScalaUtils.rowToJsonArray(row));
                return null;
            }
        });
        return list;
    }
}
