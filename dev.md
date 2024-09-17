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

自定义插件，启动报错
```
[2024-09-16T09:22:55,897][ERROR][o.e.b.Elasticsearch      ] [node1] fatal exception while booting Elasticsearchjava.lang.IllegalAccessError: superinterface check failed: class com.jd.es.auth.AuthPlugin (in unnamed module @0x7d5176d6) cannot access class org.elasticsearch.plugins.interceptor.RestServerActionPlugin (in module org.elasticsearch.server) because module org.elasticsearch.server does not export org.elasticsearch.plugins.interceptor to unnamed module @0x7d5176d6
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	at java.base/java.lang.ClassLoader.defineClass(ClassLoader.java:1023)
	at java.base/java.security.SecureClassLoader.defineClass(SecureClassLoader.java:150)
	at java.base/java.net.URLClassLoader.defineClass(URLClassLoader.java:524)
	at java.base/java.net.URLClassLoader$1.run(URLClassLoader.java:427)
```

需要修改 `./server/src/main/java/module-info.java` 文件
```
将
exports org.elasticsearch.plugins.interceptor to org.elasticsearch.security, org.elasticsearch.serverless.rest;
修改为
exports org.elasticsearch.plugins.interceptor;
```


```
input_arguments": [
          "-Des.networkaddress.cache.ttl=60",
          "-Des.networkaddress.cache.negative.ttl=10",
          "-Djava.security.manager=allow",
          "-XX:+AlwaysPreTouch",
          "-Xss1m",
          "-Djava.awt.headless=true",
          "-Dfile.encoding=UTF-8",
          "-Djna.nosys=true",
          "-XX:-OmitStackTraceInFastThrow",
          "-Dio.netty.noUnsafe=true",
          "-Dio.netty.noKeySetOptimization=true",
          "-Dio.netty.recycler.maxCapacityPerThread=0",
          "-Dlog4j.shutdownHookEnabled=false",
          "-Dlog4j2.disable.jmx=true",
          "-Dlog4j2.formatMsgNoLookups=true",
          "-Djava.locale.providers=SPI,COMPAT",
          "--add-opens=java.base/java.io=org.elasticsearch.preallocate",
          "--enable-native-access=org.elasticsearch.nativeaccess",
          "-XX:ReplayDataFile=logs/replay_pid%p.log",
          "-Des.distribution.type=tar",
          "-XX:+UseG1GC",
          "-XX:G1ReservePercent=25",
          "-XX:InitiatingHeapOccupancyPercent=30",
          "-Djava.io.tmpdir=/export/Data/elasticsearch/elastic_cluster/tmpdir",
          "-XX:ErrorFile=/export/Logs/elasticsearch/elastic_cluster/hs_err_pid%p.log",
          "-XX:+CrashOnOutOfMemoryError",
          "-Xlog:gc*,gc+age=trace,safepoint:file=/export/Logs/elasticsearch/elastic_cluster/gc.log:utctime,pid,tags:filecount=32,filesize=64m",
          "-Des.allow_insecure_settings=true",
          "-Xms4g",
          "-Xmx4g",
          "--add-exports=org.elasticsearch.server/org.elasticsearch.plugins.interceptor=ALL-UNNAMED",
          "-XX:MaxDirectMemorySize=2147483648",
          "-XX:G1HeapRegionSize=4m",
          "--module-path=/home/admin/elasticsearch-8.13.4/lib",
          "--add-modules=jdk.net",
          "--add-modules=ALL-MODULE-PATH",
          "-Djdk.module.main=org.elasticsearch.server"
        ]
```

```
#!/bin/bash
source /etc/profile

ES_NS="elastic_cluster"
NODE_NAME="elastic_cluster"
CLUSTER_NAME="elastic_cluster"
ES_JVM_HEAP=4
ES_VERSION="es8.13.4"
Data_dir="/export/Data/elasticsearch"
Jvm_tmpdir="/export/Data/elasticsearch/${ES_NS}/tmpdir"
Plugins_dir="/export/Config/elasticsearch/${ES_NS}/EsPlugins"
if [ ! -d $Data_dir ]; then
  mkdir -p $Data_dir
fi
if [ ! -d ${Jvm_tmpdir} ]; then
  mkdir -p ${Jvm_tmpdir}
fi

echo "autoStartTrue" >/tmp/elasticsearchelastic_cluster_AutoStats

PATH_CONF="/export/Config/elasticsearch/${ES_NS}"
ES_PATH="/home/admin/elasticsearch-8.13.4"
JVM_HEAP_SIZE="${ES_JVM_HEAP}g"
OPTS=" -p /tmp/elasticsearchelastic_cluster.pid -d "

if [ ! -f /tmp/elasticsearchelastic_cluster.pid ]; then
  echo "进程文件不存在,检查并创建"
  processID=$(ps -ef | grep "$ES_PATH" | grep "$NODE_NAME" | grep -v grep | awk '{print $2}')
  echo ${processID} >/tmp/elasticsearchelastic_cluster.pid
fi

PID=$(cat /tmp/elasticsearchelastic_cluster.pid)
if [ -f "/proc/${PID}/status" ]; then
  echo "prcoess $PID is runing"
  exit 0
else
  echo "准备启动ES实例"
fi

if [ ! -n "$CLUSTER_NAME" ]; then
  echo "cluster_name is null ! ERROR"
fi
if [ ! -n "$NODE_NAME" ]; then
  echo "cluster node_name is null! ERROR"
fi
if [ ! -L "${ES_PATH}/plugins" ]; then
  /bin/rm -rf ${ES_PATH}/plugins
  ln -s ${Plugins_dir} ${ES_PATH}/plugins
fi
if [ -n "$JVM_HEAP_SIZE" ]; then
  ES_JAVA_OPTS="-Xms$JVM_HEAP_SIZE -Xmx$JVM_HEAP_SIZE"
fi

export ES_JAVA_OPTS="$ES_JAVA_OPTS --add-exports org.elasticsearch.server/org.elasticsearch.plugins.interceptor=ALL-UNNAMED"

if [[ -e "${PATH_CONF}/elasticsearch.keystore.tmp" ]]; then
  rm -f ${PATH_CONF}/elasticsearch.keystore.tmp
fi
if [[ -e "${PATH_CONF}/elasticsearch.keystore" ]]; then
  rm -f ${PATH_CONF}/elasticsearch.keystore
fi


echo "Starting Elasticsearch with the options $OPTS"
ES_PATH_CONF=$PATH_CONF ${ES_PATH}/bin/elasticsearch $OPTS > start.log 2>&1 &
```


