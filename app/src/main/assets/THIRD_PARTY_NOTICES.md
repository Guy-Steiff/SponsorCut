# Third-Party Notices

SponsorCut incorporates third-party software. The complete license texts
distributed with this application are available in the
`third-party-licenses/` directory.

## FFmpeg and FFmpegKit

SponsorCut uses FFmpeg n6.0 through FFmpegKit for local media processing.

The SponsorCut FFmpegKit LTS build is configured without GPL components.
The FFmpegKit library and bundled FFmpeg libraries are distributed under the
GNU Lesser General Public License, version 3 or later. See `license.txt`.

FFmpegKit is a separate project from FFmpeg. Each project retains its own
copyright notices and license terms.

## Additional bundled components

The FFmpegKit distribution also includes:

- OpenSSL: see `license_openssl.txt`
- cpu-features: see `license_cpu_features.txt`
- GMP: see `license_gmp.txt`

Each component remains subject to its own license terms.

## SponsorBlock

SponsorCut queries the SponsorBlock public API operated by Ajay to retrieve
community-submitted YouTube segment data. SponsorCut does not incorporate
SponsorBlock source code.

SponsorBlock: https://sponsor.ajay.app/

## PostgreSQL and Neon

MegaCut’s community segment database for Rumble and Spotify uses PostgreSQL
hosted by Neon. These are server-side components of MegaCut Web, not
components distributed inside the SponsorCut Android application.

Neon: https://neon.tech/
