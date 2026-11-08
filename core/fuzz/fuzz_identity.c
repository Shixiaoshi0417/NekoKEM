/*
 * Untrusted identity keys: the hybrid public-key PEM parser (two or four
 * components) and the canonical NKID identity decoder used for signature
 * blocks. Accepted keys must survive encode/decode unchanged, so a decoder
 * that accepts a non-canonical encoding aborts instead of passing silently.
 */
#include "fuzz_common.h"

#include "../src/hybrid.h"
#include "../src/identity.h"

#include <stdio.h>
#include <string.h>

static void check_round_trip(const HybridKeys *keys,
                             const unsigned char *expected)
{
    unsigned char encoding[IDENTITY_ENCODING_SIZE];
    unsigned char again[IDENTITY_ENCODING_SIZE];
    HybridKeys decoded = {0};

    if (!identity_encode(keys, encoding) ||
        (expected != NULL &&
         memcmp(encoding, expected, IDENTITY_ENCODING_SIZE) != 0) ||
        !identity_decode(encoding, &decoded) ||
        !identity_encode(&decoded, again) ||
        memcmp(encoding, again, IDENTITY_ENCODING_SIZE) != 0) {
        abort();
    }
    hybrid_keys_cleanup(&decoded);
}

int main(int argc, char **argv)
{
    unsigned char *input = NULL;
    size_t input_len = 0U;
    HybridKeys keys = {0};

    if (argc != 2 || !fuzz_read_input(argv[1], &input, &input_len)) {
        return 0;
    }
    /* Parser diagnostics would only flood the fuzzer's output. */
    (void)freopen("/dev/null", "w", stderr);
    if (input_len == IDENTITY_ENCODING_SIZE) {
        if (identity_decode(input, &keys)) {
            check_round_trip(&keys, input);
        }
    } else if (hybrid_parse_public_keys(input, input_len, &keys) &&
               hybrid_keys_have_identity(&keys)) {
        check_round_trip(&keys, NULL);
    }
    hybrid_keys_cleanup(&keys);
    free(input);
    return 0;
}
