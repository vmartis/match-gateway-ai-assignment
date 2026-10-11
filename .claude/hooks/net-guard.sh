#!/usr/bin/env bash
# PreToolUse(Bash): internet-access policy for curl/wget (CLAUDE.md §4 "Internet access").
#   allow  – every segment is curl/wget doing an HTTPS GET/HEAD with certificate verification, sending nothing,
#            writing only to stdout or inside $CLAUDE_PROJECT_DIR (http://localhost and http://127.0.0.1 also allowed)
#   block  – exit 2: verification disabled, plain http:// to a non-local host, or the download is executed / chmod-ed
#   silent – anything else (unknown option, other commands in the chain, pipes, variables): normal permission prompt
# The command is tokenised here (quote-aware); it is never eval-ed.
# Portable to macOS /bin/bash 3.2: no associative arrays, no ${x^^}, no `set -u` (empty arrays would be "unbound").
set -o pipefail

input=$(cat)
cmd=$(jq -r '.tool_input.command // empty' <<<"$input")
cwd=$(jq -r '.cwd // empty' <<<"$input")
[[ -z "$cmd" ]] && exit 0
[[ -z "$cwd" ]] && cwd=$PWD
project=${CLAUDE_PROJECT_DIR:-$PWD}
project=${project%/}

# Cheap pre-filter: only commands that mention curl or wget as a word.
grep -Eq '(^|[;&|[:space:](])([^[:space:];&|]*/)?(curl|wget)([[:space:]]|$)' <<<"$cmd" || exit 0

block() {
  echo "BLOCKED by net-guard.sh: $1" >&2
  echo "Command: $cmd" >&2
  exit 2
}

allow() {
  printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"HTTPS GET with certificate verification"}}'
  exit 0
}

# ---------------------------------------------------------------- tokenizer
# Operators are emitted with a leading \x1f so they can never be confused with quoted words.
OP=$'\x1f'
tokens=()
dynamic=0 # $ or ` outside single quotes: the real argument values are unknown
tok="" have=0

flush() {
  if ((have)) || [[ -n $tok ]]; then tokens+=("$tok"); fi
  tok="" have=0
}

tokenize() {
  local s=$1 n=${#1} i=0 c q=""
  while ((i < n)); do
    c=${s:i:1}
    if [[ -n $q ]]; then
      if [[ $c == "$q" ]]; then
        q=""
      elif [[ $q == '"' && $c == '\' ]]; then
        i=$((i + 1)); tok+=${s:i:1}
      else
        [[ $q == '"' && ($c == '$' || $c == '`') ]] && dynamic=1
        tok+=$c
      fi
      i=$((i + 1)); continue
    fi
    case $c in
      "'" | '"') q=$c; have=1 ;;
      '\') i=$((i + 1)); tok+=${s:i:1}; have=1 ;;
      ' ' | $'\t') flush ;;
      $'\n' | ';') flush; tokens+=("${OP};") ;;
      '$' | '`') dynamic=1; tok+=$c ;;
      '|')
        flush
        if [[ ${s:i+1:1} == '|' ]]; then tokens+=("${OP};"); i=$((i + 1)); else tokens+=("${OP}|"); fi ;;
      '&')
        if [[ ${s:i+1:1} == '&' ]]; then flush; tokens+=("${OP};"); i=$((i + 1))
        elif [[ ${s:i+1:1} == '>' ]]; then flush; tokens+=("${OP}>"); i=$((i + 1)); [[ ${s:i+1:1} == '>' ]] && i=$((i + 1))
        else flush; tokens+=("${OP};"); fi ;;
      '>')
        [[ $tok =~ ^[0-9]+$ && $have -eq 0 ]] && tok=""   # fd number before >
        flush
        [[ ${s:i+1:1} == '>' ]] && i=$((i + 1))
        if [[ ${s:i+1:1} == '&' ]]; then
          i=$((i + 2)); while [[ ${s:i:1} =~ [0-9-] ]]; do i=$((i + 1)); done; i=$((i - 1)) # 2>&1: fd duplication
        else
          tokens+=("${OP}>")
        fi ;;
      '<' | '(' | ')') flush; tokens+=("${OP}complex") ;;
      *) tok+=$c ;;
    esac
    i=$((i + 1))
  done
  [[ -n $q ]] && return 1
  flush
}

tokenize "$cmd" || exit 0 # unbalanced quotes: let the normal prompt decide

# ---------------------------------------------------------------- segments
# seg_words[k] = words joined by \x1e; seg_redir[k] = redirect targets; seg_piped[k] = 1 if fed by a pipe
RS=$'\x1e'
seg_words=() seg_redir=() seg_piped=() seg_pipes_out=()
complex=0
words="" redir="" piped=0 expect_redir=0
end_segment() {
  seg_words+=("$words"); seg_redir+=("$redir"); seg_piped+=("$piped"); seg_pipes_out+=("$1")
  words="" redir=""
}
for t in "${tokens[@]}"; do
  if [[ $t == "$OP"* ]]; then
    case ${t#"$OP"} in
      ';') end_segment 0; piped=0 ;;
      '|') end_segment 1; piped=1 ;;
      '>') expect_redir=1 ;;
      complex) complex=1 ;;
    esac
    continue
  fi
  if ((expect_redir)); then redir+="$t$RS"; expect_redir=0; else words+="$t$RS"; fi
done
end_segment 0

# ---------------------------------------------------------------- helpers
inside_project() { # path -> 0 if writing there stays inside the project
  local p=$1
  [[ $p == "-" || $p == "/dev/null" ]] && return 0
  [[ $p == "~"* ]] && p="$HOME${p#\~}"
  [[ $p != /* ]] && p="$cwd/$p"
  [[ "/$p/" == */../* ]] && return 1
  [[ $p == "$project" || $p == "$project/"* ]]
}

url_basename() { # https://host/a/b.zip?x=1 -> b.zip
  local u=${1%%[?#]*}
  u=${u#*://}
  [[ $u == */* ]] || { echo ""; return; }
  echo "${u##*/}"
}

upper() { tr '[:lower:]' '[:upper:]' <<<"$1"; }
safe_method() { local m; m=$(upper "$1"); [[ $m == GET || $m == HEAD ]]; }

# Per-command results (reset for each curl/wget segment)
seg_ok=1 seg_reason=""
outputs=() downloads=()
deny_auto() { seg_ok=0; [[ -z $seg_reason ]] && seg_reason=$1; }

check_url() {
  local u=$1
  if [[ $u =~ ^https:// ]]; then return 0; fi
  if [[ $u =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?(/|$) ]]; then return 0; fi
  if [[ $u =~ ^[Hh][Tt][Tt][Pp]:// ]]; then block "plain http:// to a non-local host ($u) – use https://"; fi
  deny_auto "URL without https:// scheme"
}

check_header() {
  if grep -Eiq '^[[:space:]]*(proxy-)?(authorization|cookie)[[:space:]]*:' <<<"$1"; then deny_auto "sends credentials"; fi
}

# ---------------------------------------------------------------- curl
curl_long() { # name value -> 0 if the option consumed a value
  local name=$1 val=$2
  case $name in
    --insecure | --proxy-insecure | --doh-insecure) block "certificate verification disabled ($name)" ;;
    --cacert | --capath | --proxy-cacert | --proxy-capath | --cert | --key | --cert-type | --key-type | --pinnedpubkey | --crlfile)
      deny_auto "certificate/key override ($name)"; return 0 ;;
    --request) safe_method "$val" || deny_auto "method $val"; return 0 ;;
    --data | --data-ascii | --data-binary | --data-raw | --data-urlencode | --json | --form | --form-string | --upload-file)
      deny_auto "sends data ($name)"; return 0 ;;
    --user | --proxy-user | --oauth2-bearer | --cookie | --netrc-file | --aws-sigv4 | --cookie-jar)
      deny_auto "credentials/cookies ($name)"; return 0 ;;
    --netrc | --netrc-optional | --negotiate | --ntlm | --digest | --basic | --anyauth | --delegation)
      deny_auto "credentials ($name)"; return 1 ;;
    --header | --proxy-header) check_header "$val"; return 0 ;;
    --output | --dump-header | --trace | --trace-ascii | --stderr | --libcurl | --etag-save | --hsts | --alt-svc)
      outputs+=("$val"); return 0 ;;
    --output-dir) outdir=$val; return 0 ;;
    --remote-name | --remote-name-all | --remote-header-name) remote_name=1; return 1 ;;
    --url) urls+=("$val"); return 0 ;;
    --config | --variable | --expand-url) deny_auto "indirect configuration ($name)"; return 0 ;;
    --max-time | --connect-timeout | --retry | --retry-delay | --retry-max-time | --user-agent | --referer | --range | \
      --write-out | --max-filesize | --limit-rate | --proxy | --noproxy | --resolve | --connect-to | --proto | --proto-redir | \
      --max-redirs | --speed-limit | --speed-time | --tls-max | --interface | --continue-at | --time-cond | --dns-servers)
      return 0 ;;
    --get | --head | --location | --silent | --show-error | --fail | --fail-with-body | --fail-early | --compressed | --verbose | \
      --include | --globoff | --http1.0 | --http1.1 | --http2 | --http2-prior-knowledge | --http3 | --ipv4 | --ipv6 | \
      --remote-time | --create-dirs | --no-progress-meter | --progress-bar | --tlsv1.2 | --tlsv1.3 | --raw | --no-buffer | \
      --retry-all-errors | --retry-connrefused | --styled-output | --no-keepalive | --ssl-reqd | --tcp-nodelay | --path-as-is | --no-clobber)
      return 1 ;;
    *) deny_auto "unrecognised option $name"; return 1 ;;
  esac
  return 1
}

curl_short() { # short option that takes a value -> its long name
  case $1 in
    A) echo --user-agent ;; b) echo --cookie ;; c) echo --cookie-jar ;; C) echo --continue-at ;; d) echo --data ;;
    D) echo --dump-header ;; e) echo --referer ;; E) echo --cert ;; F) echo --form ;; H) echo --header ;;
    K) echo --config ;; m) echo --max-time ;; o) echo --output ;; P) echo --ftp-port ;; Q) echo --quote ;;
    r) echo --range ;; t) echo --telnet-option ;; T) echo --upload-file ;; u) echo --user ;; U) echo --proxy-user ;;
    w) echo --write-out ;; x) echo --proxy ;; X) echo --request ;; y) echo --speed-time ;; Y) echo --speed-limit ;;
    z) echo --time-cond ;;
  esac
}
CURL_FLAGS='0123456#:aBfgGhiIjJlLMNpqRsSvVZ'

parse_curl() {
  local -a a=("$@")
  local i=0 n=${#a[@]} w name val j ch long rest
  while ((i < n)); do
    w=${a[i]}
    if [[ $w == --* ]]; then
      if [[ $w == *=* ]]; then
        name=${w%%=*}; val=${w#*=}; curl_long "$name" "$val" >/dev/null
      else
        name=$w; val=${a[i+1]:-}
        if curl_long "$name" "$val"; then i=$((i + 1)); fi
      fi
    elif [[ $w == -?* ]]; then
      for ((j = 1; j < ${#w}; j++)); do
        ch=${w:j:1}
        if [[ $ch == k ]]; then block "certificate verification disabled (-k)"; fi
        if [[ $ch == n ]]; then deny_auto "credentials (-n/--netrc)"; continue; fi
        if [[ $ch == O ]]; then remote_name=1; continue; fi
        long=$(curl_short "$ch")
        if [[ -n $long ]]; then
          rest=${w:j+1}
          if [[ -n $rest ]]; then curl_long "$long" "$rest" >/dev/null
          else curl_long "$long" "${a[i+1]:-}" && i=$((i + 1)); fi
          break
        fi
        [[ $CURL_FLAGS == *"$ch"* ]] || deny_auto "unrecognised option -$ch"
      done
    else
      urls+=("$w")
    fi
    i=$((i + 1))
  done
}

# ---------------------------------------------------------------- wget
wget_long() { # name value -> 0 if the option consumed a value
  local name=$1 val=$2
  case $name in
    --no-check-certificate) block "certificate verification disabled ($name)" ;;
    --ca-certificate | --ca-directory | --certificate | --private-key | --crl-file | --certificate-type | --private-key-type)
      deny_auto "certificate/key override ($name)"; return 0 ;;
    --method) safe_method "$val" || deny_auto "method $val"; return 0 ;;
    --post-data | --post-file | --body-data | --body-file) deny_auto "sends data ($name)"; return 0 ;;
    --user | --password | --http-user | --http-password | --ftp-user | --ftp-password | --proxy-user | --proxy-password | --load-cookies | --save-cookies)
      deny_auto "credentials/cookies ($name)"; return 0 ;;
    --ask-password | --use-askpass) deny_auto "credentials ($name)"; return 1 ;;
    --header) check_header "$val"; return 0 ;;
    --output-document) outputs+=("$val"); return 0 ;;
    --output-file | --append-output) outputs+=("$val"); return 0 ;;
    --directory-prefix) outdir=$val; return 0 ;;
    --execute | --input-file | --config | --base) deny_auto "indirect configuration ($name)"; return 0 ;;
    --tries | --timeout | --wait | --waitretry | --user-agent | --referer | --progress | --max-redirect | --quota | --level | \
      --accept | --reject | --domains | --include-directories | --exclude-directories | --limit-rate | --read-timeout | --connect-timeout)
      return 0 ;;
    --quiet | --verbose | --no-verbose | --server-response | --spider | --timestamping | --continue | --no-clobber | --show-progress | \
      --https-only | --content-disposition | --recursive | --no-parent | --page-requisites | --convert-links | --mirror | \
      --adjust-extension | --no-host-directories | --no-directories | --inet4-only | --inet6-only | --debug)
      [[ $name == --spider ]] && spider=1
      return 1 ;;
    *) deny_auto "unrecognised option $name"; return 1 ;;
  esac
  return 1
}

wget_short() { # short option that takes a value -> its long name
  case $1 in
    O) echo --output-document ;; o) echo --output-file ;; a) echo --append-output ;; P) echo --directory-prefix ;;
    e) echo --execute ;; i) echo --input-file ;; t) echo --tries ;; T) echo --timeout ;; w) echo --wait ;;
    Q) echo --quota ;; U) echo --user-agent ;; l) echo --level ;; A) echo --accept ;; R) echo --reject ;;
    B) echo --base ;; D) echo --domains ;; I) echo --include-directories ;; X) echo --exclude-directories ;;
  esac
}
WGET_FLAGS='qvcNSrpkKmExFHLbd46'

parse_wget() {
  local -a a=("$@")
  local i=0 n=${#a[@]} w name val j ch long rest
  while ((i < n)); do
    w=${a[i]}
    if [[ $w == --* ]]; then
      if [[ $w == *=* ]]; then
        name=${w%%=*}; val=${w#*=}; wget_long "$name" "$val" >/dev/null
      else
        name=$w; val=${a[i+1]:-}
        if wget_long "$name" "$val"; then i=$((i + 1)); fi
      fi
    elif [[ $w =~ ^-n(v|c|d|H|p)$ ]]; then
      : # -nv -nc -nd -nH -np are single options
    elif [[ $w == -?* ]]; then
      for ((j = 1; j < ${#w}; j++)); do
        ch=${w:j:1}
        long=$(wget_short "$ch")
        if [[ -n $long ]]; then
          rest=${w:j+1}
          if [[ -n $rest ]]; then wget_long "$long" "$rest" >/dev/null
          else wget_long "$long" "${a[i+1]:-}" && i=$((i + 1)); fi
          break
        fi
        [[ $WGET_FLAGS == *"$ch"* ]] || deny_auto "unrecognised option -$ch"
      done
    else
      urls+=("$w")
    fi
    i=$((i + 1))
  done
}

# ---------------------------------------------------------------- evaluate segments
program_of() { # words... -> index of the program word (skips VAR=value prefixes)
  local k=0
  for w in "$@"; do
    [[ $w =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || { echo "$k"; return; }
    k=$((k + 1))
  done
  echo -1
}

all_net=1 all_ok=1 any_net=0 fail_reason=""
declare -a other_segments=()
for k in "${!seg_words[@]}"; do
  IFS=$RS read -r -a w <<<"${seg_words[k]%"$RS"}" || true
  ((${#w[@]} == 0)) && continue
  p=$(program_of "${w[@]}")
  ((p < 0)) && { all_net=0; continue; }
  prog=${w[p]##*/}
  args=("${w[@]:p+1}")
  if [[ $prog != curl && $prog != wget ]]; then
    all_net=0
    other_segments+=("$k")
    continue
  fi
  any_net=1
  seg_ok=1 seg_reason="" urls=() outputs=() outdir="" remote_name=0 spider=0
  if [[ $prog == curl ]]; then parse_curl "${args[@]}"; else parse_wget "${args[@]}"; fi
  ((${#urls[@]} == 0)) && deny_auto "no URL"
  for u in "${urls[@]}"; do check_url "$u"; done

  # Where does the download land?
  IFS=$RS read -r -a r <<<"${seg_redir[k]%"$RS"}" || true
  for f in "${r[@]}"; do [[ -n $f ]] && outputs+=("$f"); done
  if [[ $prog == wget && ${#outputs[@]} -eq 0 && $spider -eq 0 ]] || ((remote_name)); then
    for u in "${urls[@]}"; do
      b=$(url_basename "$u"); [[ -z $b ]] && b=index.html
      outputs+=("${outdir:+$outdir/}$b")
    done
  elif [[ -n $outdir ]]; then
    outputs=("${outputs[@]/#/$outdir/}")
  fi
  for f in "${outputs[@]}"; do
    [[ $f == "-" || $f == /dev/null ]] && continue
    downloads+=("$f")
    inside_project "$f" || deny_auto "writes outside the project ($f)"
  done
  ((seg_pipes_out[k])) && deny_auto "output is piped"
  ((seg_ok)) || { all_ok=0; [[ -z $fail_reason ]] && fail_reason=$seg_reason; }
done

((any_net)) || exit 0

# ---------------------------------------------------------------- block: executing the download
is_download() {
  local x=$1 d
  for d in "${downloads[@]}"; do
    [[ $x == "$d" || ${x##*/} == "${d##*/}" ]] && return 0
  done
  return 1
}
for k in "${!seg_words[@]}"; do
  IFS=$RS read -r -a w <<<"${seg_words[k]%"$RS"}" || true
  ((${#w[@]} == 0)) && continue
  p=$(program_of "${w[@]}"); ((p < 0)) && continue
  prog=${w[p]}
  base=${prog##*/}
  case $base in
    bash | sh | zsh | dash | ksh | source | .)
      ((seg_piped[k])) && block "downloaded content piped into $base"
      for x in "${w[@]:p+1}"; do is_download "$x" && block "executes the downloaded file ($x)"; done ;;
    chmod)
      for x in "${w[@]:p+1}"; do is_download "$x" && block "chmod on the downloaded file ($x)"; done ;;
    *)
      [[ $prog == */* ]] && is_download "$prog" && block "executes the downloaded file ($prog)" ;;
  esac
done

# ---------------------------------------------------------------- allow only the plain, fully verified case
((all_net && all_ok && !dynamic && !complex)) && allow
exit 0
