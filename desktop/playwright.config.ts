import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'tests/visual',use:{baseURL:'http://127.0.0.1:1420',viewport:{width:1100,height:780}},webServer:{command:'npm run dev',url:'http://127.0.0.1:1420',reuseExistingServer:!process.env.CI}});
