### Remap

This is a fork of https://github.com/ReplayMod/remap for use with unimined.
it will be dynamically polled by unimined only when needed and run in a seperate process to fufil the plug-in requirements
of GPL 3, for unimined itself to not have to be GPL 3.

![from johni0702: no. though it does not force mods into being GPL (compatible), only their build scripts (and those are probably either already gpl compatible or not distributed in the first place): https://www.gnu.org/licenses/gpl-faq.html#WhatCaseIsOutputGPL
and depending on how you interact with it, it might not even infect anything at all: https://www.gnu.org/licenses/gpl-faq.html#GPLPlugins (its current literal main method doesn't have all the options, but its more general interface too is basically just "set some options, invoke, wait for it to return").](img.png)


Towards that end, I still need to modify the remapper for use in unimined, thus this repo distributes the changed source code,
as well as allowing me to upload it to my maven easiser.

## License
The Remap is provided under the terms of the GNU General Public License Version 3 or (at your option) any later version.
See `LICENSE.md` for the full license text.
