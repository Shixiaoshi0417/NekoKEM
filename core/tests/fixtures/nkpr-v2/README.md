# NKPR v2 fixture

A throwaway identity key pair written by NekoKEM 5.2.0 (`nekokem keygen`):
`private.key.enc` is an NKPR v2 container, the format without key
commitment. Tests use it to check that newer versions still read NKPR v2 and
upgrade it to NKPR v3.

The password is `nkpr-v2-fixture-password`. The key protects nothing; never
use it outside tests. Git does not keep mode 0600, so tests copy the private
key and restrict the copy before use.
