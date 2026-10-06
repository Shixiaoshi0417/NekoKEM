<script setup lang="ts">
const paths = {
  keygen: ['M14 5a5 5 0 1 1-3.7 8.4L4 20H2v-3l6.6-6.3A5 5 0 0 1 14 5Z', 'M16 8h.01', 'M5 17l2 2'],
  encrypt: ['M6 11h12v10H6z', 'M8 11V7a4 4 0 0 1 8 0v4', 'M12 15v2'],
  decrypt: ['M6 11h12v10H6z', 'M8 11V7a4 4 0 0 1 7.5-2', 'M12 15v2'],
  fingerprint: ['M4 12a8 8 0 0 1 16 0v2', 'M7 17v-5a5 5 0 0 1 10 0v4c0 2-.5 3.5-1.5 5', 'M10 20v-8a2 2 0 0 1 4 0v5', 'M4 16v2'],
  contacts: ['M5 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H5Z', 'M11.5 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z', 'M7.5 17a4 4 0 0 1 8 0', 'M18 7h2', 'M18 11h2', 'M18 15h2'],
  exit: ['M10 4H4v16h6', 'M10 12h11', 'm17 8 4 4-4 4'],
  arrow: ['M5 12h14', 'm13 6 6 6-6 6'],
  shield: ['m12 3 8 3v6c0 4-3.5 7-8 9-4.5-2-8-5-8-9V6Z', 'm8 12 3 3 5-5'],
  check: ['m5 12 4 4L19 6'],
  alert: ['M12 3 2 21h20Z', 'M12 9v5', 'M12 17h.01'],
  close: ['m6 6 12 12', 'M18 6 6 18'],
} as const;
defineProps<{ name: keyof typeof paths }>();
</script>

<template>
  <svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path v-for="(path, index) in paths[name]" :key="index" :d="path" /></svg>
</template>
