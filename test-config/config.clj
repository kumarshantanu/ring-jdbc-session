;; Comment out the elements you don't want to test with; add entries as needed.
;;
;; First element in each vector is the option map passed to all functions. Rest
;; of the elements are passed to the `clj-dbcp/make-datasource` function.

[[{} :h2 {:target :memory :database "default"}]
 ;;[{} :mysql {:host "localhost" :database "appdb" :user "joe" :password "s3cr3t"}]
 ;;[{} :postgresql {:host "localhost" :database "appdb" :user "joe" :password "s3cr3t"}]
 ]
