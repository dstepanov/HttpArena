wrk.method = "POST"
wrk.body = string.rep("x", 10240)
wrk.headers["Content-Type"] = "application/octet-stream"
