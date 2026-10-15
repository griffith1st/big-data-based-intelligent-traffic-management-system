#!/usr/bin/env python3
"""ITMS read API load test. Python 3.10+, standard library only.

Closed-loop, bounded concurrent workers, persistent HTTP/1.1 connections.
No redirects or automatic retries. Credentials and response bodies never enter reports.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import platform
import socket
import subprocess
import sys
import time
from urllib.parse import urlsplit


ENDPOINTS = ("/api/traffic/realtime", "/api/traffic/heatmap", "/api/traffic/summary")
MAX_RESPONSE_BYTES = 2 * 1024 * 1024


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", help="default BASE_URL or http://127.0.0.1:8080; explicit argument required for remote hosts")
    parser.add_argument("--concurrency", type=positive_int, default=10)
    parser.add_argument("--requests", type=positive_int, default=300)
    parser.add_argument("--timeout", type=float, default=3.0, help="socket timeout in seconds (not an absolute total-request deadline)")
    parser.add_argument("--warmup-requests", type=int, default=0, help="excluded from measurements; uses the same concurrency")
    parser.add_argument("--max-error-rate", type=float, default=0.01, help="failure threshold; 429 responses count as errors")
    parser.add_argument("--p95-ms", type=float, default=0, help="optional all-request latency threshold; 0 disables")
    parser.add_argument("--token-env", default="TOKEN", help="environment variable holding the JWT, without Bearer prefix")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[2] / "target" / "load-results")
    args = parser.parse_args()
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be finite and positive")
    if not math.isfinite(args.max_error_rate) or not 0 <= args.max_error_rate <= 1:
        parser.error("--max-error-rate must be between 0 and 1")
    if not math.isfinite(args.p95_ms) or args.p95_ms < 0 or args.warmup_requests < 0:
        parser.error("--p95-ms and --warmup-requests must be nonnegative")
    if args.concurrency > 1000:
        parser.error("--concurrency maximum is 1000")
    base_url = args.base_url or os.getenv("BASE_URL", "http://127.0.0.1:8080")
    try:
        url = urlsplit(base_url)
        valid = (url.scheme in ("http", "https") and url.hostname and url.port != 0
                 and not url.username and not url.password and not url.query and not url.fragment)
    except ValueError:
        valid = False
    if not valid:
        parser.error("base URL must use http(s), a valid host/port, and no credentials/query/fragment")
    try:
        loopback = ipaddress.ip_address(url.hostname).is_loopback
    except ValueError:
        loopback = url.hostname.lower() == "localhost"
    if not loopback and args.base_url is None:
        parser.error("remote hosts require an explicit --base-url argument")
    token = os.getenv(args.token_env, "").strip()
    if not token or "\r" in token or "\n" in token:
        parser.error("JWT must be present in the selected token environment variable")
    args.base_url = base_url.rstrip("/")
    return args, url, token


def measure_worker(worker_id, workers, count, url, token, timeout):
    results = []
    connection_type = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    connection = connection_type(url.hostname, url.port, timeout=timeout)
    try:
        for index in range(worker_id, count, workers):
            endpoint = ENDPOINTS[index % len(ENDPOINTS)]
            status = None
            business_code = None
            started = time.perf_counter()
            outcome = "network_error"
            try:
                connection.request("GET", url.path.rstrip("/") + endpoint,
                                   headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
                response = connection.getresponse()
                status = response.status
                payload = response.read(MAX_RESPONSE_BYTES + 1)
                if len(payload) > MAX_RESPONSE_BYTES:
                    connection.close()
                    outcome = "response_too_large"
                else:
                    try:
                        body = json.loads(payload)
                        code = body.get("code") if isinstance(body, dict) else None
                        # Only numeric codes are retained; body text may contain sensitive data.
                        business_code = code if isinstance(code, int) and not isinstance(code, bool) else None
                    except (ValueError, UnicodeDecodeError):
                        business_code = None
                    if status == 429 or (200 <= status < 300 and business_code == 429):
                        outcome = "rate_limited"
                    elif 500 <= status < 600:
                        outcome = "http_5xx"
                    elif not 200 <= status < 300:
                        outcome = "http_other_error"
                    elif business_code is None:
                        outcome = "invalid_body"
                    elif business_code != 200:
                        outcome = "business_error"
                    else:
                        outcome = "success"
            except (TimeoutError, socket.timeout):
                outcome = "timeout"
                connection.close()
            except (OSError, http.client.HTTPException, ValueError):
                # Exception messages can include request details; retain a category only.
                outcome = "network_error"
                connection.close()
            results.append({"endpoint": endpoint, "status": status, "body_code": business_code,
                            "outcome": outcome, "latency_ms": (time.perf_counter() - started) * 1000})
    finally:
        connection.close()
    return results


def run_load(count, concurrency, url, token, timeout):
    workers = min(count, concurrency)
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(measure_worker, index, workers, count, url, token, timeout)
                   for index in range(workers)]
        results = [item for future in futures for item in future.result()]
    return results, time.perf_counter() - started


def percentiles(values):
    if not values:
        return {"p50_ms": None, "p95_ms": None, "p99_ms": None, "max_ms": None}
    values = sorted(values)
    # Nearest-rank percentile, not interpolation. Include failures in all-request distribution.
    result = {f"p{p}_ms": round(values[max(0, math.ceil(len(values) * p / 100) - 1)], 3)
              for p in (50, 95, 99)}
    return {**result, "max_ms": round(values[-1], 3)}


def summarize(results, duration):
    counts = Counter(row["outcome"] for row in results)
    total = len(results)
    status_counts = Counter(str(row["status"]) for row in results if row["status"] is not None)
    return {
        "total": total, "success": counts["success"], "rate_limited": counts["rate_limited"],
        "http_429": sum(row["status"] == 429 for row in results),
        "http_5xx": sum(row["status"] is not None and 500 <= row["status"] < 600 for row in results),
        "business_error": counts["business_error"], "timeout": counts["timeout"],
        "outcomes": dict(counts), "http_status_counts": dict(status_counts),
        "error_rate": (total - counts["success"]) / total if total else 0,
        "duration_seconds": round(duration, 6),
        "completed_rps": round(total / duration, 3) if duration else 0,
        "success_rps": round(counts["success"] / duration, 3) if duration else 0,
        "latency_all": percentiles([row["latency_ms"] for row in results]),
        "latency_success": percentiles([row["latency_ms"] for row in results if row["outcome"] == "success"]),
    }


def machine_info():
    memory = None
    try:
        if os.name == "nt":
            import ctypes
            class MemoryStatus(ctypes.Structure):
                _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                    (name, ctypes.c_ulonglong) for name in ("total_physical", "available_physical",
                    "total_page_file", "available_page_file", "total_virtual", "available_virtual", "extended_virtual")]
            info = MemoryStatus()
            info.length = ctypes.sizeof(info)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(info)):
                memory = info.total_physical
        else:
            memory = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (AttributeError, OSError, ValueError):
        pass
    return {"platform": platform.platform(), "python": platform.python_version(),
            "cpu": platform.processor(), "logical_cpus": os.cpu_count(), "physical_memory_bytes": memory,
            "scope": "load generator only; record server/container resources separately"}


def git_info():
    root = Path(__file__).resolve().parents[3]
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True,
                                text=True, timeout=5, check=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain"], cwd=root, capture_output=True,
                               text=True, timeout=5, check=True).stdout.strip()
        return {"commit": commit, "working_tree_dirty": bool(dirty)}
    except (OSError, subprocess.SubprocessError):
        return {"commit": None, "working_tree_dirty": None}


def write_reports(report, directory):
    directory.mkdir(parents=True, exist_ok=True)
    stem = "traffic-load-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    json_path = directory / (stem + ".json")
    markdown_path = directory / (stem + ".md")
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = report["summary"]
    lines = ["# ITMS read API load evidence", "", f"Started (UTC): {report['started_at']}", "",
             f"Result: **{'PASS' if report['passed'] else 'FAIL'}**", "",
             "This is a closed-loop HTTP read test; it is not a production capacity certificate.",
             "429, HTTP errors, timeouts, invalid JSON and non-200 business codes are failures.", "",
             "## Parameters", "", "```json", json.dumps(report["parameters"], ensure_ascii=False, indent=2), "```", "",
             "## Results", "", "| Metric | Value |", "| --- | ---: |"]
    for key in ("total", "success", "rate_limited", "http_429", "http_5xx", "business_error", "timeout",
                "duration_seconds", "completed_rps", "success_rps", "error_rate"):
        lines.append(f"| {key} | {summary[key]} |")
    lines.extend(["", "## Latency (milliseconds; nearest-rank)", "",
                  "| Population | P50 | P95 | P99 | Max |", "| --- | ---: | ---: | ---: | ---: |"])
    for name in ("latency_all", "latency_success"):
        values = summary[name]
        lines.append(f"| {name} | {values['p50_ms']} | {values['p95_ms']} | {values['p99_ms']} | {values['max_ms']} |")
    lines.extend(["", "## Per endpoint", "", "| Endpoint | Total | Success | 429/rate limited | HTTP 5xx | P95 ms |",
                  "| --- | ---: | ---: | ---: | ---: | ---: |"])
    for endpoint, data in report["by_endpoint"].items():
        lines.append(f"| {endpoint} | {data['total']} | {data['success']} | {data['rate_limited']} | {data['http_5xx']} | {data['latency_all']['p95_ms']} |")
    lines.extend(["", "## Load generator and revision", "", "```json",
                  json.dumps({"load_generator": report["load_generator"], "git": report["git"]}, ensure_ascii=False, indent=2),
                  "```", "", "Warmup is excluded. Connections are reused per worker. Redirects and automatic retries are disabled.",
                  "Server CPU/RAM, JVM flags, data volume, cache state, Kafka lag and database metrics must be attached separately.", ""])
    markdown_path.write_text("\n".join(lines), encoding="utf-8")
    return json_path, markdown_path


def main():
    args, url, token = arguments()
    if args.warmup_requests:
        warmup, warmup_duration = run_load(args.warmup_requests, args.concurrency, url, token, args.timeout)
        warmup_summary = summarize(warmup, warmup_duration)
    else:
        warmup_summary = None
    started_at = datetime.now(timezone.utc).isoformat()
    results, duration = run_load(args.requests, args.concurrency, url, token, args.timeout)
    summary = summarize(results, duration)
    passed = (summary["error_rate"] <= args.max_error_rate and
              (args.p95_ms == 0 or summary["latency_all"]["p95_ms"] <= args.p95_ms))
    report = {
        "schema_version": 1, "started_at": started_at, "passed": passed,
        "parameters": {"base_url": args.base_url, "concurrency": args.concurrency, "requests": args.requests,
                       "timeout_seconds": args.timeout, "warmup_requests": args.warmup_requests,
                       "max_error_rate": args.max_error_rate, "p95_threshold_ms": args.p95_ms,
                       "mix": {endpoint: "1/3 round-robin" for endpoint in ENDPOINTS},
                       "model": "closed-loop; one in-flight request per worker", "credentials": "environment only; omitted"},
        "load_generator": machine_info(), "git": git_info(), "summary": summary,
        "warmup_summary": warmup_summary,
        "by_endpoint": {endpoint: summarize([row for row in results if row["endpoint"] == endpoint], duration)
                        for endpoint in ENDPOINTS},
    }
    paths = write_reports(report, args.output_dir)
    print(f"{'PASS' if passed else 'FAIL'}: success={summary['success']}/{summary['total']}, "
          f"success_rps={summary['success_rps']}, rate_limited={summary['rate_limited']}, "
          f"http_5xx={summary['http_5xx']}, p95_ms={summary['latency_all']['p95_ms']}")
    for path in paths:
        print(path.resolve())
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Interrupted; completed evidence was not written.", file=sys.stderr)
        sys.exit(130)
    except OSError:
        print("Load tool failed to read/write local resources; check output directory permissions.", file=sys.stderr)
        sys.exit(2)
