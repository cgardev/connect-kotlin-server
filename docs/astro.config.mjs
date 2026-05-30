// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// Published as a GitHub Pages project site at
// https://cgardev.github.io/connect-kotlin-server/, so the origin and the base
// path are configured separately.
export default defineConfig({
	site: 'https://cgardev.github.io',
	base: '/connect-kotlin-server',
	integrations: [
		starlight({
			title: 'connect-kotlin-server',
			description:
				'Server-side Connect and gRPC-Web for the JVM — serve your grpc-java services over the Connect protocol with an embedded Netty server.',
			social: [
				{
					icon: 'github',
					label: 'GitHub',
					href: 'https://github.com/cgardev/connect-kotlin-server',
				},
			],
			editLink: {
				baseUrl: 'https://github.com/cgardev/connect-kotlin-server/edit/main/docs/',
			},
			sidebar: [
				{
					label: 'Start Here',
					items: [
						{ label: 'Introduction', link: '/' },
						{ label: 'Getting Started', slug: 'getting-started' },
					],
				},
				{
					label: 'Guides',
					items: [
						{ label: 'Architecture', slug: 'guides/architecture' },
						{ label: 'Protocols & Codecs', slug: 'guides/protocols-and-codecs' },
						{ label: 'Spring Boot', slug: 'guides/spring-boot' },
						{ label: 'Configuration', slug: 'guides/configuration' },
						{ label: 'Error Handling', slug: 'guides/error-handling' },
						{ label: 'Calling from the browser', slug: 'guides/connect-web-clients' },
					],
				},
				{
					label: 'Reference',
					items: [{ label: 'API Reference', slug: 'reference/api' }],
				},
			],
		}),
	],
});
