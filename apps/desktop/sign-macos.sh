#!/bin/sh
# Signs the built macOS app so your Mac will open it.
#
# Rust leaves a "linker-signed" bundle behind: nothing seals the app's
# resources, so macOS treats every launch as a stranger's code and the
# app loses whatever you granted it last time.
#
#   (nothing set)              ad-hoc: a sealed bundle, opened after one
#                              right-click → Open
#   GBOT_SIGN_IDENTITY="…"     your own certificate (see the README): same
#                              one-time approval, but macOS then knows the
#                              app between builds
#   APPLE_SIGNING_IDENTITY     a real Developer ID. Tauri has already signed
#                              and notarized the bundle, so this leaves it be.
set -e

app=apps/desktop/src-tauri/target/release/bundle/macos/gbot.app
[ -d "$app" ] || { echo "no app at $app"; exit 0; }

if [ -n "$APPLE_SIGNING_IDENTITY" ]; then
	echo "signed by Tauri as $APPLE_SIGNING_IDENTITY"
else
	id="${GBOT_SIGN_IDENTITY:--}"
	codesign --force --deep --timestamp=none --sign "$id" "$app"
	[ "$id" = "-" ] && echo "signed ad hoc" || echo "signed as $id"
fi

# A fresh build is not quarantined, but a copy that came through a DMG,
# AirDrop or a download is: that flag is what makes macOS refuse it.
xattr -dr com.apple.quarantine "$app" 2>/dev/null || true

codesign --verify --deep --strict "$app" && echo "$app is ready"
