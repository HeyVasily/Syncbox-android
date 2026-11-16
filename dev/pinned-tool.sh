#!/usr/bin/env bash
# Official release binaries, verified by SHA-256 and cached in .build/tools.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
name="${1:?usage: pinned-tool.sh actionlint|zizmor|gitleaks|osv-scanner|bundletool}"
case "$(uname -sm)" in
  'Linux x86_64') arch=amd64; triple=x86_64-unknown-linux-gnu ;;
  'Linux aarch64') arch=arm64; triple=aarch64-unknown-linux-gnu ;;
  *) printf 'unsupported lint tool platform\n' >&2; exit 1 ;;
esac
case "$name" in
  actionlint)
    version=1.7.12
    # https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_checksums.txt
    case "$arch" in
      amd64) checksum=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 ;;
      arm64) checksum=325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6 ;;
    esac
    url="https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_linux_$arch.tar.gz"
    member=actionlint
    ;;
  zizmor)
    version=1.31.1
    # Official release API assets[].digest SHA-256 values (no checksum manifest):
    # https://api.github.com/repos/zizmorcore/zizmor/releases/tags/v1.31.1
    case "$arch" in
      amd64) checksum=d185f726bdaf92df78273217dfdd24ebcd6b0ef48cf3ce776ff5762b6e138c99 ;;
      arm64) checksum=37eebbd5762a44f262d94875431b14a6d2402991e90a685113ea97b8b41be833 ;;
    esac
    url="https://github.com/zizmorcore/zizmor/releases/download/v$version/zizmor-$triple.tar.gz"
    member=zizmor
    ;;
  gitleaks)
    version=8.30.1
    # https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_checksums.txt
    case "$arch" in
      amd64) checksum=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb; garch=x64 ;;
      arm64) checksum=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080; garch=arm64 ;;
    esac
    url="https://github.com/gitleaks/gitleaks/releases/download/v$version/gitleaks_${version}_linux_$garch.tar.gz"
    member=gitleaks
    ;;
  osv-scanner)
    version=2.7.0
    # https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_SHA256SUMS
    case "$arch" in
      amd64) checksum=160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b ;;
      arm64) checksum=4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7 ;;
    esac
    url="https://github.com/google/osv-scanner/releases/download/v$version/osv-scanner_linux_$arch"
    member=archive
    ;;
  bundletool)
    # A Java archive: the same file on every platform. Run it with java -jar.
    version=1.18.3
    # Official release API assets[].digest SHA-256 value (no checksum manifest):
    # https://api.github.com/repos/google/bundletool/releases/tags/1.18.3
    checksum=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
    url="https://github.com/google/bundletool/releases/download/$version/bundletool-all-$version.jar"
    member=archive
    ;;
  *) printf 'unknown pinned tool: %s\n' "$name" >&2; exit 1 ;;
esac
tools="$ROOT/.build/tools"
tool="$tools/$name-$version-$arch"
if [ ! -x "$tool" ]; then
  mkdir -p "$tools"
  download="$(mktemp -d "$tools/$name.XXXXXX")"
  trap 'rm -rf -- "$download"' EXIT
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    --retry 6 --retry-all-errors --retry-max-time 600 --connect-timeout 20 --max-time 300 \
    "$url" --output "$download/archive"
  printf '%s  %s\n' "$checksum" "$download/archive" | sha256sum --check --status
  if [ "$member" != archive ]; then
    tar -xzf "$download/archive" -C "$download" "$member"
  fi
  install -m 0755 "$download/$member" "$download/tool"
  mv "$download/tool" "$tool"
fi
case "$name" in
  actionlint) actual="$("$tool" -version)"; expected="$version" ;;
  zizmor) actual="$("$tool" --version)"; expected="zizmor $version" ;;
  gitleaks) actual="$("$tool" version)"; expected="$version" ;;
  osv-scanner) actual="$("$tool" --version)"; expected="osv-scanner version: $version" ;;
  bundletool) actual="$(java -jar "$tool" version)"; expected="$version" ;;
esac
[ "${actual%%$'\n'*}" = "$expected" ] || { printf 'unexpected %s version\n' "$name" >&2; exit 1; }
printf '%s\n' "$tool"
