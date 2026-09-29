#!/usr/bin/env bash
# shellcheck shell=bash
#
# SyncTank contract-check — helpers shared by every script in this directory.
#
# Every script here also runs OUTSIDE GitHub Actions (guide §9, the local dry run). So each
# helper degrades to plain stdout when the runner's files ($GITHUB_OUTPUT, $GITHUB_STEP_SUMMARY)
# do not exist, instead of failing on an unset variable.

# Where this action keeps its working files. $RUNNER_TEMP is per-job and wiped afterwards;
# locally it falls back to /tmp.
work_root() {
  printf '%s/synctank-contract-check\n' "${RUNNER_TEMP:-/tmp}"
}

# Per-API working directory, e.g. $RUNNER_TEMP/synctank-contract-check/orders-backend
work_dir() {
  printf '%s/%s\n' "$(work_root)" "$1"
}

# Relative paths are resolved against the workspace, never against wherever a step happened to
# `cd` to. Day 05's hardest lesson: the platform resolves paths against ITS working directory.
abs_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *)  printf '%s/%s\n' "${GITHUB_WORKSPACE:-$PWD}" "$1" ;;
  esac
}

set_output() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
  fi
  printf '[output] %s=%s\n' "$1" "$2"
}

summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"
  fi
}

group()    { echo "::group::$*"; }
endgroup() { echo "::endgroup::"; }

# wait_for URL NAME TRIES — polls every 2 s. Returns 1 (never exits) so the caller decides
# what to print before failing.
wait_for() {
  local url=$1 name=$2 tries=${3:-30} i
  for i in $(seq 1 "$tries"); do
    if curl -sf "$url" > /dev/null 2>&1; then
      echo "$name is up (attempt $i)"
      return 0
    fi
    sleep 2
  done
  echo "::error title=Contract check::$name never became healthy at $url"
  return 1
}