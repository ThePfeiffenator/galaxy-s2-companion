original_failure = 0x7cd37;
original_success = 0x7cc87;
next_dictionary_entry = 0x7ccc1;
runtime_free = 0x388cc;
remote_property_table = 0xc4ef4;
SECTIONS {
    .hook 0x7cc82 : { *(.hook) }
    .compatibility 0xbb490 : { *(.compatibility) }
    /DISCARD/ : { *(.ARM.exidx*) *(.ARM.extab*) *(.comment) *(.note*) }
}
