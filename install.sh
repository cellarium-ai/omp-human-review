#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${OMP_REVIEW_DIR:-$HOME/.omp-review}"

if [ -d "$INSTALL_DIR/.git" ]; then
  echo "Updating $INSTALL_DIR ..."
  git -C "$INSTALL_DIR" pull --ff-only
else
  echo "Cloning into $INSTALL_DIR ..."
  git clone https://github.com/cellarium-ai/omp-human-review.git "$INSTALL_DIR"
fi

omp plugin link "$INSTALL_DIR/omp"
echo "✓ omp-review installed. Restart OMP for the extension to take effect."
