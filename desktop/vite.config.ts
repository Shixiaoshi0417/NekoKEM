import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
export default defineConfig({ plugins:[vue()], clearScreen:false, server:{port:1420,strictPort:true,host:'127.0.0.1'}, test:{environment:'jsdom',include:['tests/**/*.test.ts']} });
