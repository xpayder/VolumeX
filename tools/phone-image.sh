#!/bin/bash
# Debug helper: run the app on the connected phone against a raw disk image instead of a USB drive.
#   tools/phone-image.sh push <image>      copy an image into the app's private storage
#   tools/phone-image.sh open <name>       start the app with that image mounted
#   tools/phone-image.sh pull <name> <out> copy the (modified) image back to this Mac
A=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}; PKG=app.feldkit; DIR=/data/data/$PKG/files
case "$1" in
  push) n=$(basename "$2"); $A push "$2" /data/local/tmp/$n >/dev/null && $A shell "run-as $PKG sh -c 'mkdir -p $DIR && cp /data/local/tmp/$n $DIR/$n'" && $A shell rm /data/local/tmp/$n && echo "pushed $n" ;;
  open) $A shell am force-stop $PKG; $A shell am start -n $PKG/com.fatalpuppet.volumex.MainActivity --es vx_image $DIR/$2 >/dev/null && echo "opened $2" ;;
  pull) $A exec-out "run-as $PKG cat $DIR/$2" > "$3" && echo "pulled $2 -> $3 ($(stat -f %z "$3") bytes)" ;;
  put)  n=$(basename "$2"); $A push "$2" /data/local/tmp/$n >/dev/null && $A shell "run-as $PKG sh -c 'cp /data/local/tmp/$n $DIR/$n'" && $A shell rm /data/local/tmp/$n && echo "put $n" ;;
  import) $A shell am start -n $PKG/com.fatalpuppet.volumex.MainActivity --activity-single-top --es vx_import $DIR/$2 >/dev/null && echo "import requested: $2" ;;
  cd) $A shell am start -n $PKG/com.fatalpuppet.volumex.MainActivity --activity-single-top --es vx_cd "$2" >/dev/null && echo "cd $2" ;;
  *) echo "usage: $0 push|put <file> | open <image> | import <name> | cd <path> | pull <name> <out>"; exit 1 ;;
esac
