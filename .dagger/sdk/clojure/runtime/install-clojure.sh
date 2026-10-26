#!/bin/sh
set -eu
version=1.12.6.1673
curl -fsSL "https://download.clojure.org/install/clojure-tools-${version}.tar.gz" -o /tmp/clojure-tools.tar.gz
tar -xzf /tmp/clojure-tools.tar.gz -C /tmp
prefix=/usr/local/lib/clojure
mkdir -p "$prefix/libexec" /usr/local/bin
install -m644 /tmp/clojure-tools/deps.edn /tmp/clojure-tools/example-deps.edn /tmp/clojure-tools/tools.edn "$prefix/"
install -m644 /tmp/clojure-tools/exec.jar "/tmp/clojure-tools/clojure-tools-${version}.jar" "$prefix/libexec/"
sed "s@PREFIX@$prefix@g" /tmp/clojure-tools/clojure > /usr/local/bin/clojure
sed 's@BINDIR@/usr/local/bin@g' /tmp/clojure-tools/clj > /usr/local/bin/clj
chmod +x /usr/local/bin/clojure /usr/local/bin/clj
rm -rf /tmp/clojure-tools /tmp/clojure-tools.tar.gz
