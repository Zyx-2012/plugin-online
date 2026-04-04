import { viteConfig } from "@halo-dev/ui-plugin-bundler-kit";
import path from "path";

export default viteConfig({
  vite: {
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "src"),
      },
    },
  },
});