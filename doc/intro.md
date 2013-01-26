# Introduction to ring-jdbc-session

TODO: write [great documentation](http://jacobian.org/writing/great-documentation/what-to-write/)

The `README.md` file shows the example using the defaults. Here we will look at
the options you can provide to override the defaults. All function names belong
to the namespace `ring-jdbc-session.core`.

## `create-session-table` - Creates the session table in database

Example:

```clojure
(create-session-table conn-factory)
(create-session-table conn-factory {:val-column-type "VARCHAR(4096)"})
```

| Required argument                              | Option        | Default value    |
|------------------------------------------------|---------------|------------------|
| conn-factory (`javax.sql.DataSource` instance) | `:table-name` | `"ring_session"` |
|                                                | `:key-column` | `"session_key"`  |
|                                                | `:val-column` | `"session_val"`  |
|                                                | `:ts-column`  | `"session_ts"`   |
|                                                | `:key-column-type` | `"VARCHAR(100) UNIQUE NOT NULL"` |
|                                                | `:val-column-type` | `"VARCHAR(1024)"`      |
|                                                | `:ts-column-type`  | `"TIMESTAMP NOT NULL"` |

When using this library in an app, it may make sense to create the session table
using your favourite database-migration workflow instead of this function. The
required columns are below:

| Column                  | Default name    | Clojure data type    |
|-------------------------|-----------------|----------------------|
| Session key             | `"session_key"` | String               |
| Session data            | `"session_val"` | As per `make-session-store :serializer` |
| Last-accessed timestamp | `"session_ts"`  | `java.sql.Timestamp` |

For production use consider *Session key* column to have a Hash (or BTree if Hash
not supported) index and *Last-accessed timestamp* column to have a BTree index.

## `drop-session-table` - Drops the session table from database

Example:

```clojure
(drop-session-table conn-factory)
(drop-session-table conn-factory {:table-name "http_session"})
```

| Required argument                              | Option        | Default value    |
|------------------------------------------------|---------------|------------------|
| conn-factory (`javax.sql.DataSource` instance) | `:table-name` | `"ring_session"` |

## `make-session-store` - Creates the JDBC session store

Example:

```clojure
(make-session-store conn-factory)
(make-session-store conn-factory {:table-name "http_session" :prefix "web2"})
```

| Required argument                              | Option          | Default value    |
|------------------------------------------------|-----------------|------------------|
| conn-factory (`javax.sql.DataSource` instance) | `:table-name`   | `"ring_session"` |
|                                                | `:key-column`   | `"session_key"`  |
|                                                | `:val-column`   | `"session_val"`  |
|                                                | `:ts-column`    | `"session_ts"`   |
|                                                | `:serializer`   | `clojure.core/pr-str`      |
|                                                | `:deserializer` | `clojure.core/read-string` |
|                                                | `:prefix`       | `"session"`      |
|                                                | `:expire-secs`  | `1800` (30 mins) |

Note that `:expire-secs` is in seconds because the SQL-92 standard allows
*second* precision to be supported by databases, even though certain databases
may support finer precision than *second*.

## `start-cleaner` - Starts background cleaner to remove stale keys

This function returns a `Stoppable` instance that you can pass to `stop-cleaner`
as argument.

```clojure
(start-cleaner jdbc-store)
(start-cleaner jdbc-store {:interval-secs 3600})  ; once an hour
```

| Required argument                    | Option           | Default value  |
|--------------------------------------|------------------|----------------|
| session-store (`JdbcStore` instance) | `:interval-secs` | `60` (seconds) |

## `stop-cleaner` - Stops a cleaner

```clojure
(stop-cleaner cleaner)
```
