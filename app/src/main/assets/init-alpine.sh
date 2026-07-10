#!/bin/sh
# OpenCode Alpine initialization (based on Acode's init-alpine.sh)

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8

# If a command was supplied, execute it and exit
if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then
    exec "$@"
fi

# Install essential packages on first run
required_packages="bash git nodejs npm curl"
missing_packages=""

for pkg in $required_packages; do
    if ! apk info -e "$pkg" >/dev/null 2>&1; then
        missing_packages="$missing_packages $pkg"
    fi
done

if [ -n "$missing_packages" ]; then
    echo -e "\e[34;1m[*] \e[0mInstalling packages:\e[0m$missing_packages"
    apk update && apk upgrade
    apk add $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully installed\e[0m"
    fi
fi

# Launch bash
exec /bin/bash --rcfile /etc/profile -i
