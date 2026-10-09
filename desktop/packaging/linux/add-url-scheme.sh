#!/bin/sh
# Registers octo:// links in a finished Octo .deb or .rpm, so a family
# link's "Open in Octo" opens the app from the first moment it is
# installed:
#
#   - the package's menu entry (the .desktop file under /opt/octo/lib)
#     gains x-scheme-handler/octo in its MimeType and %U in its Exec line,
#     so the link reaches the app;
#   - the post-install script, after its own lines, refreshes the system's
#     list of handlers (update-desktop-database) and names Octo the handler
#     (xdg-mime), each only when the system has it.
#
# The installed app still writes its own entry at its first start
# (system/LinkRegistration.kt), for a system without either tool.
#
#   add-url-scheme.sh <package.deb|package.rpm>
#
# Needs dpkg-deb for a .deb, and rpm2cpio, cpio and rpmbuild for an .rpm:
# the tools that made the package. Running it twice changes nothing more.

set -eu

package=$(readlink -f "$1")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The lines the post-install script gains, marked so a second run finds
# them. A .deb runs them on "configure" only; an .rpm's %post always.
post_lines() {
    if [ "$1" = deb ]; then when='[ "$1" = configure ]'; else when='true'; fi
    cat <<EOF
# octo:// links open Octo.
if $when; then
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
    if command -v xdg-mime >/dev/null 2>&1; then
        xdg-mime default octo-Octo.desktop x-scheme-handler/octo || true
    fi
fi
# End of octo:// links.
EOF
}

# Adds the scheme to every Octo menu entry in a folder tree.
fix_entries() {
    find "$1" -type f -name '*.desktop' -path '*/opt/*' | while read -r entry; do
        if ! grep -q 'x-scheme-handler/octo' "$entry"; then
            if grep -q '^MimeType=' "$entry"; then
                sed -i 's|^MimeType=\(.*\)$|MimeType=\1x-scheme-handler/octo;|; s|^MimeType=\(.*[^;]\)x-scheme-handler/octo;$|MimeType=\1;x-scheme-handler/octo;|' "$entry"
            else
                echo 'MimeType=x-scheme-handler/octo;' >> "$entry"
            fi
        fi
        # The link reaches the app as its first argument.
        if ! grep -q '^Exec=.*%[uUfF]' "$entry"; then
            sed -i 's|^Exec=\(.*\)$|Exec=\1 %U|' "$entry"
        fi
        echo "menu entry: ${entry#$1}"
    done
}

# Puts the lines into a post-install script before its last "exit 0", or at
# its end.
add_post() {
    script=$1
    grep -q '^# octo:// links open Octo.$' "$script" && return 0
    lines=$(post_lines "$2")
    if grep -q '^exit 0' "$script"; then
        last=$(grep -n '^exit 0' "$script" | tail -n 1 | cut -d: -f1)
        head -n $((last - 1)) "$script" > "$work/post.new"
        printf '%s\n' "$lines" >> "$work/post.new"
        tail -n +"$last" "$script" >> "$work/post.new"
    else
        cat "$script" > "$work/post.new"
        printf '%s\n' "$lines" >> "$work/post.new"
    fi
    cat "$work/post.new" > "$script"
}

case "$package" in
*.deb)
    root="$work/root"
    dpkg-deb -R "$package" "$root"
    fix_entries "$root"
    if [ ! -f "$root/DEBIAN/postinst" ]; then
        printf '#!/bin/sh\nset -e\nexit 0\n' > "$root/DEBIAN/postinst"
    fi
    add_post "$root/DEBIAN/postinst" deb
    chmod 0755 "$root/DEBIAN/postinst"
    # The changed entry's checksum.
    if [ -f "$root/DEBIAN/md5sums" ]; then
        (cd "$root" && find opt -type f -name '*.desktop' | while read -r f; do
            sum=$(md5sum "$f" | cut -d' ' -f1)
            sed -i "s|^[0-9a-f]*  $f\$|$sum  $f|" DEBIAN/md5sums
        done)
    fi
    dpkg-deb --root-owner-group -b "$root" "$package" >/dev/null
    ;;
*.rpm)
    root="$work/root"
    mkdir -p "$root" "$work/rpmbuild/RPMS"
    (cd "$root" && rpm2cpio "$package" | cpio -idm --quiet)
    fix_entries "$root"
    # The package's own scripts and details, carried into a new spec.
    rpm -qp --queryformat '%{POSTIN}' "$package" | sed '/^(none)$/d' > "$work/post"
    add_post "$work/post" rpm
    spec="$work/octo.spec"
    {
        rpm -qp --queryformat 'Name: %{NAME}\nVersion: %{VERSION}\nRelease: %{RELEASE}\nSummary: %{SUMMARY}\nLicense: %{LICENSE}\nGroup: %{GROUP}\nURL: %{URL}\nAutoReqProv: no\n' "$package" | sed '/: (none)$/d'
        rpm -qp --requires "$package" | grep -v '^rpmlib(' | sed 's/^/Requires: /'
        printf '\n%%description\n'
        rpm -qp --queryformat '%{DESCRIPTION}\n' "$package"
        for part in PREIN PREUN POSTUN; do
            body=$(rpm -qp --queryformat "%{$part}" "$package")
            if [ "$body" != "(none)" ]; then
                case $part in PREIN) printf '\n%%pre\n' ;; PREUN) printf '\n%%preun\n' ;; POSTUN) printf '\n%%postun\n' ;; esac
                printf '%s\n' "$body"
            fi
        done
        printf '\n%%post\n'
        cat "$work/post"
        printf '\n%%files\n'
        rpm -qlp "$package" | while read -r f; do
            if [ -d "$root$f" ] && [ ! -L "$root$f" ]; then printf '%%dir "%s"\n' "$f"; else printf '"%s"\n' "$f"; fi
        done
    } > "$spec"
    rpmbuild -bb --quiet --define "_topdir $work/rpmbuild" --define "_build_id_links none" --buildroot "$root" "$spec"
    cp "$(find "$work/rpmbuild/RPMS" -name '*.rpm' | head -n 1)" "$package"
    ;;
*)
    echo "Not a .deb or .rpm: $package" >&2
    exit 1
    ;;
esac
echo "octo:// is registered by $package"
