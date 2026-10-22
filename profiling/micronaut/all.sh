#!/bin/bash
# Runs every profiled path. usage: all.sh [tuned|production]
# Environment as for run.sh, plus WRK, GCANNON (load generators) and the repo's requests/ directory (REQ).
set -u
here=$(cd "$(dirname "$0")" && pwd)
: "${WRK:?wrk binary}" "${GCANNON:?gcannon binary}" "${REQ:=$here/../../requests}"
W=$WRK; G=$GCANNON; R=$REQ; X=$here/run.sh; e=${1:-tuned}
if [ "$e" = tuned ]; then
$X $e baseline-get   $W -t2 -c256 -d22s "'http://localhost:8080/baseline11?a=13&b=42'"
$X $e baseline-mix   $G http://localhost:8080 --raw $R/get.raw,$R/post_cl.raw,$R/post_chunked.raw -c 256 -t 2 -d 22 -p 1
$X $e baseline2      $W -t2 -c256 -d22s "'http://localhost:8080/baseline2?a=1&b=1'"
$X $e async          $G http://localhost:8080 --raw $R/async-delay.raw -c 256 -t 2 -d 22
$X $e static-br      $W -t2 -c256 -d22s -H "'Accept-Encoding: br'" http://localhost:8080/static/app.js
$X $e pipelined      $G http://localhost:8080/pipeline -c 256 -t 2 -d 22 -p 16
$X $e tls-h1         $W -t2 -c256 -d22s "'https://localhost:8081/json/25?m=1'"
$X $e h2-tls         h2load -D 22 -c 64 -m 32 -t 2 "'https://localhost:8443/baseline2?a=1&b=1'"
$X $e h2c            h2load -D 22 -c 64 -m 100 -t 2 -p h2c "'http://localhost:8082/baseline2?a=1&b=1'"
$X $e ws-echo        $G http://localhost:8080/ws --ws -c 256 -t 2 -d 22 -p 1
else
$X $e baseline-get   $W -t2 -c256 -d22s "'http://localhost:8080/baseline11?a=13&b=42'"
fi
$X $e json           $G http://localhost:8080 --raw $R/json-get.raw -c 256 -t 2 -d 22
$X $e json-comp      $G http://localhost:8080 --raw $R/json-gzip-25.raw,$R/json-gzip-40.raw,$R/json-gzip-50.raw -c 256 -t 2 -d 22
$X $e static-plain   $W -t2 -c256 -d22s http://localhost:8080/static/reset.css
$X $e echo           $W -t2 -c64 -d22s -s $here/load/echo10k.lua http://localhost:8080/echo
$X $e async-db       $G http://localhost:8080 --raw $R/async-db-5.raw,$R/async-db-10.raw,$R/async-db-20.raw,$R/async-db-35.raw,$R/async-db-50.raw -c 128 -t 2 -d 22
$X $e fortunes       $G http://localhost:8080 --raw $R/fortunes-get.raw -c 128 -t 2 -d 22
echo ALLDONE
