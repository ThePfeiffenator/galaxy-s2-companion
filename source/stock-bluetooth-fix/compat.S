.syntax unified
.thumb
.section .hook,"ax",%progbits
.global compatibility_hook
.type compatibility_hook,%function
.thumb_func
compatibility_hook:
    b.w services_compatibility

.section .compatibility,"ax",%progbits
.global services_compatibility
.type services_compatibility,%function
.thumb_func
services_compatibility:
    /* Original cmp/bne overwritten at 0x7cc82: preserve every error. */
    cmp r0, #0
    bne.w original_failure
    ldr.w r0, [r7, #0xa4]
    cmp r0, #5
    bne.w original_success
    ldr r1, table_delta
pc_for_table:
    add r1, pc
    cmp r11, r1
    bne.w original_success
    /* Exact remote Services was decoded but not counted or marked present.
       Release only its temporary pointer array, never the borrowed strings. */
    ldr.w r0, [r7, #0xb0]
    cbz r0, no_allocation
    blx runtime_free
no_allocation:
    b.w next_dictionary_entry
    .balign 4
table_delta:
    .word remote_property_table - (pc_for_table + 4)

