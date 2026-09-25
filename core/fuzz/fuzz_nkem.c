#include "fuzz_common.h"

#include "../src/file.h"

int main(int argc, char **argv)
{
    unsigned char *input = NULL;
    size_t input_len = 0U;

    if (argc != 2 || !fuzz_read_input(argv[1], &input, &input_len)) {
        return 0;
    }
    (void)nkem_v3_container_parse(input, input_len);
    free(input);
    return 0;
}
