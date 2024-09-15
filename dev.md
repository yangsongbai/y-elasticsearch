# 构建
```
./gradlew  :distribution:archives:darwin-tar:assemble  -Dbuild.snapshot=false -Dlicense.key=licenses/GPG-KEY-elasticsearch
```


# es配置
elasticsearch.yml
```
cluster.name: test_es_cluster
node.name: node1
node.roles: [data, master]
network.host: 127.0.01
http.port: 9200
transport.port: 9300
discovery.seed_hosts: ["127.0.0.1:9300"]
cluster.initial_master_nodes: ["127.0.0.1:9300"]
indices.fielddata.cache.size: 10%
indices.queries.cache.size: 10%
bootstrap.memory_lock: false
xpack.ml.enabled: false
xpack.watcher.enabled: false
xpack.security.enabled : false
xpack.security.transport.ssl.enabled: false
xpack.security.http.ssl.enabled: false
```
jvm.options
同版本不兼容问题
```
-Des.unsafely_permit_handshake_from_incompatible_builds=true
```

jdk/lib/security/default.policy
```
grant {
    permission java.lang.RuntimePermission "createClassLoader";
    permission java.net.SocketPermission "*:*","connect,resolve";
	permission java.lang.RuntimePermission "getClassLoader";
    permission java.lang.RuntimePermission "setContextClassLoader";
    permission java.lang.RuntimePermission "createClassLoader";
    permission java.lang.RuntimePermission "accessDeclaredMembers";
    permission java.lang.reflect.ReflectPermission "suppressAccessChecks";
    permission java.util.PropertyPermission "*","read,write";
    permission java.lang.RuntimePermission "*";
};

```
