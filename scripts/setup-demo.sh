#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists; keeping its passwords.'
  exit 0
fi
umask 077
# noclobber also protects against concurrent setup calls.
set -C
{
  printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'READER_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'TECHNICIAN_PASSWORD=%s\n' "$(openssl rand -hex 24)"
} > .env
printf '%s\n' 'Created local demo passwords in .env. Keep this file private.'
