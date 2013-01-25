# ring-jdbc-session

A Clojure library to implement an HTTP session store for
[Ring](https://github.com/ring-clojure/ring) using a JDBC backing store.

Tested with Clojure versions 1.3, 1.4 and 1.5-RC3 with the following databases:

* H2 in-memory database
* MySQL
* PostgreSQL

## Usage

### Leiningen dependency

On Clojars: _Not on Clojars yet_

### Pre-requisite

1. First of all, you need a connection-pooling `javax.sql.DataSource` instance
for use with this library. A `DataSource` is like a connection factory. See
[Clj-DBCP](https://github.com/kumarshantanu/clj-dbcp) (example below uses this) and
[c3p0](http://clojure.github.com/java.jdbc/doc/clojure/java/jdbc/ConnectionPooling.html)
on how to create a `DataSource`. Let's assume the `DataSource` is bound to a var `ds`.

```clojure
(def ds (clj-dbcp.core/make-datasource :mysql {:host "localhost" :database "abc"
                                               :user "dbuser" :password "s3cr3t"}))
```

2. Create the session table if not already created:

```clojure
(ring-jdbc-session.core/create-session-table ds)
```

### Create and use the session store

#### Use with Ring

```clojure
(let [jdbc-ss (ring-jdbc-session.core/make-session-store ds)]
  (ring-jdbc-session.core/start-cleaner jdbc-ss) ; starting cleaner is optional
  (ring.middleware.session/wrap-session handler {:store jdbc-ss}))
```

#### Use with Compojure

```clojure
(let [jdbc-ss (ring-jdbc-session.core/make-session-store ds)]
  (ring-jdbc-session.core/start-cleaner jdbc-ss) ; starting cleaner is optional
  (compojure.handler/site handler {:session {:store jdbc-ss}}))
```

## Documentation

See file `doc/intro.md` in this repo.

## Getting in touch

On [Ring discussion group](https://groups.google.com/forum/?fromgroups=#!forum/ring-clojure)

On Twitter: [@kumarshantanu](https://twitter.com/kumarshantanu)

## License

Copyright © 2013 Shantanu Kumar

Distributed under the Eclipse Public License, the same as Clojure.
