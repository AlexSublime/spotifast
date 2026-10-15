CPAL 0.15.3 source from crates.io, Apache-2.0.

The manifest retains only the Android/Oboe backend dependencies. Other
platform dependencies are omitted because this copy is selected only by the
Android build; keeping its Linux ALSA dependency would conflict with the
desktop CPAL 0.18 dependency during Cargo resolution.
