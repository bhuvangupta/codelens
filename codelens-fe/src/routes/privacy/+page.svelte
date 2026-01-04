<script lang="ts">
	import { goto } from '$app/navigation';
</script>

<svelte:head>
	<title>Privacy Policy - CodeLens</title>
</svelte:head>

<div class="min-h-screen bg-gray-50">
	<!-- Header -->
	<header class="bg-white border-b border-gray-200">
		<div class="max-w-4xl mx-auto px-4 py-4 flex items-center gap-4">
			<button onclick={() => goto('/')} class="text-gray-500 hover:text-gray-700">
				<svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
				</svg>
			</button>
			<div class="flex items-center gap-3">
				<img src="/codelens.png" alt="CodeLens" class="w-8 h-8" />
				<span class="font-semibold text-gray-900">CodeLens</span>
			</div>
		</div>
	</header>

	<!-- Content -->
	<main class="max-w-4xl mx-auto px-4 py-8">
		<div class="bg-white rounded-lg shadow-sm border border-gray-200 p-8">
			<h1 class="text-3xl font-bold text-gray-900 mb-2">Privacy Policy</h1>
			<p class="text-sm text-gray-500 mb-8">Last updated: January 2, 2026</p>

			<div class="prose prose-gray max-w-none">
				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">1. Introduction</h2>
				<p class="text-gray-600 mb-4">
					CodeLens ("we", "our", or "us") is committed to protecting your privacy. This Privacy Policy explains how we collect, use, disclose, and safeguard your information when you use our AI-powered code review service.
				</p>
				<p class="text-gray-600 mb-4">
					We comply with the General Data Protection Regulation (GDPR), California Consumer Privacy Act (CCPA), and other applicable data protection laws.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">2. Information We Collect</h2>

				<h3 class="text-lg font-medium text-gray-800 mt-6 mb-3">2.1 Information You Provide</h3>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li><strong>Account Information:</strong> When you sign in with Google, we collect your name, email address, and profile picture.</li>
					<li><strong>Organization Data:</strong> Organization name and settings you configure.</li>
					<li><strong>Git Provider Tokens:</strong> GitHub and GitLab access tokens you provide for repository access (stored encrypted).</li>
				</ul>

				<h3 class="text-lg font-medium text-gray-800 mt-6 mb-3">2.2 Information We Collect Automatically</h3>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li><strong>Code Data:</strong> Pull request diffs, code changes, and related metadata submitted for review.</li>
					<li><strong>Usage Data:</strong> Review history, analytics, and service interaction logs.</li>
					<li><strong>Technical Data:</strong> IP address, browser type, and device information.</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">3. How We Use Your Information</h2>
				<p class="text-gray-600 mb-4">We use the collected information to:</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Provide AI-powered code review services</li>
					<li>Authenticate and manage your account</li>
					<li>Post review comments to your Git repositories</li>
					<li>Generate analytics and usage reports</li>
					<li>Improve our services and develop new features</li>
					<li>Communicate important service updates</li>
					<li>Comply with legal obligations</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">4. Legal Basis for Processing (GDPR)</h2>
				<p class="text-gray-600 mb-4">We process your personal data based on:</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li><strong>Contract Performance:</strong> Processing necessary to provide our services to you.</li>
					<li><strong>Legitimate Interests:</strong> Improving our services, security, and fraud prevention.</li>
					<li><strong>Consent:</strong> Where you have explicitly consented to specific processing.</li>
					<li><strong>Legal Obligation:</strong> Compliance with applicable laws and regulations.</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">5. Data Sharing and Disclosure</h2>
				<p class="text-gray-600 mb-4">We may share your information with:</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li><strong>LLM Providers:</strong> Code snippets are sent to AI providers (GLM, Claude, Gemini, OpenAI, Ollama) for analysis. No personally identifiable information is included.</li>
					<li><strong>Git Providers:</strong> Review comments are posted to GitHub/GitLab using your provided tokens.</li>
					<li><strong>Service Providers:</strong> Infrastructure and hosting providers who assist in operating our service.</li>
					<li><strong>Legal Requirements:</strong> When required by law or to protect our rights.</li>
				</ul>
				<p class="text-gray-600 mb-4">
					<strong>We do not sell your personal data.</strong>
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">6. Data Retention</h2>
				<p class="text-gray-600 mb-4">
					We retain your data for as long as your account is active or as needed to provide services. Review data is retained for analytics purposes but can be deleted upon request. After account deletion, we may retain certain data for legal compliance for up to 90 days.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">7. Your Rights</h2>
				<p class="text-gray-600 mb-4">Under GDPR and other privacy laws, you have the right to:</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li><strong>Access:</strong> Request a copy of your personal data.</li>
					<li><strong>Rectification:</strong> Correct inaccurate or incomplete data.</li>
					<li><strong>Erasure:</strong> Request deletion of your personal data ("right to be forgotten").</li>
					<li><strong>Restriction:</strong> Request limitation of processing.</li>
					<li><strong>Portability:</strong> Receive your data in a structured, machine-readable format.</li>
					<li><strong>Objection:</strong> Object to processing based on legitimate interests.</li>
					<li><strong>Withdraw Consent:</strong> Withdraw consent at any time where processing is based on consent.</li>
				</ul>
				<p class="text-gray-600 mb-4">
					To exercise these rights, contact us at <a href="mailto:privacy@example.com" class="text-primary-600 hover:underline">privacy@example.com</a>.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">8. Data Security</h2>
				<p class="text-gray-600 mb-4">
					We implement appropriate technical and organizational measures to protect your data, including:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Encryption of data in transit (TLS) and at rest</li>
					<li>Secure storage of access tokens</li>
					<li>Regular security assessments</li>
					<li>Access controls and authentication</li>
					<li>Audit logging of sensitive operations</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">9. International Data Transfers</h2>
				<p class="text-gray-600 mb-4">
					Your data may be transferred to and processed in countries outside your residence. We ensure appropriate safeguards are in place, including Standard Contractual Clauses (SCCs) where required by GDPR.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">10. Children's Privacy</h2>
				<p class="text-gray-600 mb-4">
					Our service is not directed to individuals under 16. We do not knowingly collect personal data from children. If we become aware of such collection, we will delete it promptly.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">11. Changes to This Policy</h2>
				<p class="text-gray-600 mb-4">
					We may update this Privacy Policy periodically. We will notify you of material changes by posting the new policy on this page and updating the "Last updated" date. Continued use of the service after changes constitutes acceptance.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">12. Contact Us</h2>
				<p class="text-gray-600 mb-4">
					For privacy-related inquiries or to exercise your rights:
				</p>
				<ul class="list-none text-gray-600 mb-4 space-y-1">
					<li>Email: <a href="mailto:privacy@example.com" class="text-primary-600 hover:underline">privacy@example.com</a></li>
				</ul>
				<p class="text-gray-600 mb-4">
					You also have the right to lodge a complaint with your local data protection authority.
				</p>
			</div>
		</div>

		<!-- Footer Links -->
		<div class="mt-8 text-center text-sm text-gray-500">
			<a href="/terms" class="hover:text-primary-600">Terms of Service</a>
			<span class="mx-2">·</span>
			<a href="/" class="hover:text-primary-600">Back to Login</a>
		</div>
	</main>
</div>
