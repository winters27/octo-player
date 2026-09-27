# Test fixtures

Small lossy files the decoder tests read. Both are pure sine tones made by
`tools/make-fixtures`, so no recorded music is checked in. WAV and FLAC test
files are made while the tests run instead.

| File | Contents |
| --- | --- |
| `tone-440-44k-mono.mp3` | 1 s of 440 Hz, 44.1 kHz mono, 96 kbps CBR, with the LAME header holding the encoder delay and padding |
| `tone-440-48k-stereo.opus` | 1 s of 440 Hz, 48 kHz stereo, Ogg Opus at 128 kbps, pre-skip 312 |

To make them again:

```sh
cd tools/make-fixtures
cargo run --release
```
