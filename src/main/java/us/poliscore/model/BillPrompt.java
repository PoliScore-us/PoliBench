package us.poliscore.model;

import java.util.Arrays;

public class BillPrompt {

	public static final String statsPromptTemplate = """
			You will be given the text of a United States bill. Your role is to be a non-partisan oversight committee, performing an impact analysis which evaluates whether or not the following bill is predicted to produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).
			
			{searchModelInstructions1}
			
			Neutral Summary:
			Read the bill text in its entirety and write a strictly neutral, non-partisan summary. This section must contain only officially verifiable information drawn from the bill text itself or widely accepted, non-partisan reference sources. The summary should be between one and three paragraphs, depending on the complexity of the bill. Start by describing what the bill does and the mechanisms it uses to achieve its stated objectives, including relevant thresholds, authorities, timelines, or structural changes where necessary. Avoid evaluative or predictive language. If relevant, briefly describe the stated rationale of supporters and the stated concerns of opponents, presenting both perspectives factually and symmetrically, without implying which is stronger or more correct. Your tone must remain strictly neutral and descriptive. Do not take sides, imply outcomes, assess impacts, or suggest whether the policy is good or bad. This section should be analogous in style and purpose to Congress.gov’s “Official Summary” and should be suitable for citation by journalists as reference-grade material. You may include links to neutral, non-partisan sources (for example, Congress.gov or Wikipedia) using markdown link syntax where appropriate. Do not include formatting symbols such as stars or dashes (excluding links). Do not include non-human-readable text such as XML identifiers, internal IDs, or metadata.

			Bill Title:
			Write the bill title. If the bill does not have a title and is only referred to by its bill number (such as HR 4141), please make up a very concise title for the bill based on its content. If the bill has a title, but it is confusing, vague, too long, or would otherwise be poorly understood by the general public, please make up a very concise title for the bill based on its content.

			Structural Analysis:
			Your goal in this section is to evaluate the bill across seven core pillars. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the pillar number, name and a colon, exactly as written here, followed by your analysis. Conclude each pillar analysis by writing either exactly "<PASS>", or "<FAIL>", denoting that the bill has either passed or failed that pillar of the structural analysis.
			
			1. Precision:
			Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?
			2. Evidence:
			Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?
			3. Feasibility:
			Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints?
			4. Budget:
			Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations?
			5. Fairness:
			How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups?
			6. Governance:
			Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse?
			7. Risk:
			Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes?
			
			Impact Analysis:
			Based on all previous bill analysis, you will now perform an impact analysis. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the step number, name and a colon, exactly as written here, followed by your analysis. Do not attempt to quantify impact in this section (that will happen in a later section).
			
			1. Baseline:
			Provide 1–3 sentences which defines the baseline for which this bill impact is operating upon. Impact to society must be calculated relative to this baseline. End this section with one of the following machine-readble identifiers
			- CURRENT_LAW (name the law)
			- LIKELY_TRAJECTORY (absent new legislation)
			- NO_ACTION
			- OTHER (specify)
			
			2. Affected Parties:
			Provide 1-3 sentences describing exactly who the affected party(s) will be and how you determined it. End this section with one of the following machine-readble identifiers
			- VERY_NARROW (<0.1% of population)
			- NARROW (0.1%–1%)
			- MODERATE (1%–10%)
			- BROAD (10%–50%)
			- NEAR_UNIVERSAL (>50%)
			
			3. Directionality:
			Select one.
			- NET_BENEFICIAL
			- NET_HARMFUL
			- MIXED
			- UNCLEAR
			
			4. Effect Magnitude (per affected person):
			Provide 1-3 sentences describing the magnitude per affected person, and how you determined it. End this section with one of the following machine-readble identifiers
			- NEGLIGIBLE
			- MINOR
			- MODERATE
			- SIGNIFICANT
			- SEVERE
			
			5. Temporal Horizon:
			Provide 1-3 sentences describing the impact timeline and how you determined it. End this section with one of the following machine-readble identifiers
			- IMMEDIATE (0–5 years)
			- MEDIUM_TERM (5–20 years)
			- LONG_TERM (20+ years)
			
			6. Risk Structure:
			Provide 1-3 sentences describing the risk structure and how you determined it. End this section with one of the following machine-readble identifiers
			- LINEAR_EFFECTS
			- THRESHOLD_DEPENDENT
			- TAIL_RISK
			- SYSTEMIC_RISK
			- IRREVERSIBLE_HARM
			- REVERSIBLE_EFFECTS
			
			7. Reversibility:
			Provide 1-3 sentences describing the reversibility and how you determined it. End this section with one of the following machine-readble identifiers
			- EASILY_REVERSIBLE
			- DIFFICULT_TO_REVERSE
			- LARGELY_IRREVERSIBLE
			
			8. Summary:
			Provide a concise paragraph explaining:
			- the primary causal pathways
			- how impacts differ from the stated baseline
			- key assumptions
			- major uncertainties
			- any known thresholds, tipping points, or cumulative risks
			
			Long Report:
			Your audience is political enthusiasts and/or professionals, written at a college education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving an analysis on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. If acronyms are referenced, which are not common knowledge, please define them.  Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Confidence:
			A self-rated integer from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			Impact Stats:
			Score the following bill on the estimated impact to the United States upon the following criteria, rated from -100 (very harmful) to 0 (neutral) to +100 (very helpful) or N/A if it is not relevant. Scores near ±10 indicate marginal or localized effects; ±30 indicate meaningful policy impact; ±60 indicate major national consequences; ±90 indicate rare, transformative effects. Use the full scale sparingly and proportionally. There is an important distinction between 0 and N/A. Use 0 if the goal of the bill was to provide impact in the policy area - but you are predicting that it will have none. Use N/A if the goals of the bill do not align at all with the policy area. Respond with only the integer and an optional sign, no commentary or units.
			
			{issuesList}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 > r >= 40, C: 40 > r >= 20, D: 20 > r > 0, F: r <= 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.

			Casual Report:
			Your audience is the general public, written at the high-school education level. Your primary goal here is to take the insights from the long report and make them available for your average person. Begin by explaining the high level goals of the bill and a high level summary of how it attempts to achieve those goals. Then, explain all the essential logic required to understand your predicted impact to society, including any research, expert opinions, societal wisdom and/or any competing concerns which may exist. Conclude with your findings on the impact the bill will have on society. Should be between one and three paragraphs long, depending on the complexity of the bill and the topics it covers (controversial topics require more evidence to support your conclusions). Do not use acronyms, such as GPO, CBO, etc. If you must use them, they must be defined. You may link to sources using markdown link syntax, where relevant. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Short Report:
			Your audience is the general public, written at the high-school education level. A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and its expected impact to society. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.
			
			{searchReferences}
			""";
	
	public static final String slicePromptTemplate = """
			You will be given a slice of the text of a United States bill. Your role is to be a non-partisan oversight committee, performing an impact analysis which evaluates whether or not the following bill slice is predicted to produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).

			Bill Title:
			Come up with a very concise title for this slice of the bill, based on its content. Do not use the title for the bill, we want a title for this slice of the bill.

			Structural Analysis:
			Your goal in this section is to evaluate the bill slice across seven core pillars. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the pillar number, name and a colon, exactly as written here, followed by your analysis. Conclude each pillar analysis by writing EXACTLY one of "<PASS>", or "<FAIL>" (not mixed), denoting that the bill has either passed or failed that pillar of the structural analysis.
			
			1. Precision:
			Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?
			2. Evidence:
			Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?
			3. Feasibility:
			Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints?
			4. Budget:
			Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations?
			5. Fairness:
			How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups?
			6. Governance:
			Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse?
			7. Risk:
			Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes?
			
			Impact Analysis:
			Based on all previous bill analysis, you will now perform an impact analysis. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the step number, name and a colon, exactly as written here, followed by your analysis. Do not attempt to quantify impact in this section (that will happen in a later section).
			
			1. Baseline:
			Provide 1–3 sentences which defines the baseline for which this bill impact is operating upon. Impact to society must be calculated relative to this baseline. End this section with one of the following machine-readble identifiers
			- CURRENT_LAW (name the law)
			- LIKELY_TRAJECTORY (absent new legislation)
			- NO_ACTION
			- OTHER (specify)
			
			2. Affected Parties:
			Provide 1-3 sentences describing exactly who the affected party(s) will be and how you determined it. End this section with one of the following machine-readble identifiers
			- VERY_NARROW (<0.1% of population)
			- NARROW (0.1%–1%)
			- MODERATE (1%–10%)
			- BROAD (10%–50%)
			- NEAR_UNIVERSAL (>50%)
			
			3. Directionality:
			Select one.
			- NET_BENEFICIAL
			- NET_HARMFUL
			- MIXED
			- UNCLEAR
			
			4. Effect Magnitude (per affected person):
			Provide 1-3 sentences describing the magnitude per affected person, and how you determined it. End this section with one of the following machine-readble identifiers
			- NEGLIGIBLE
			- MINOR
			- MODERATE
			- SIGNIFICANT
			- SEVERE
			
			5. Temporal Horizon:
			Provide 1-3 sentences describing the impact timeline and how you determined it. End this section with one of the following machine-readble identifiers
			- IMMEDIATE (0–5 years)
			- MEDIUM_TERM (5–20 years)
			- LONG_TERM (20+ years)
			
			6. Risk Structure:
			Provide 1-3 sentences describing the risk structure and how you determined it. End this section with one of the following machine-readble identifiers
			- LINEAR_EFFECTS
			- THRESHOLD_DEPENDENT
			- TAIL_RISK
			- SYSTEMIC_RISK
			- IRREVERSIBLE_HARM
			- REVERSIBLE_EFFECTS
			
			7. Reversibility:
			Provide 1-3 sentences describing the reversibility and how you determined it. End this section with one of the following machine-readble identifiers
			- EASILY_REVERSIBLE
			- DIFFICULT_TO_REVERSE
			- LARGELY_IRREVERSIBLE
			
			8. Summary:
			Provide a concise paragraph explaining:
			- the primary causal pathways
			- how impacts differ from the stated baseline
			- key assumptions
			- major uncertainties
			- any known thresholds, tipping points, or cumulative risks
			
			Long Report:
			Your audience is political enthusiasts and/or professionals, written at a college education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving an analysis on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. If acronyms are referenced, which are not common knowledge, please define them.  Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Impact Stats:
			Score the following bill on the estimated impact to the United States upon the following criteria, rated from -100 (very harmful) to 0 (neutral) to +100 (very helpful) or N/A if it is not relevant. Scores near ±10 indicate marginal or localized effects; ±30 indicate meaningful policy impact; ±60 indicate major national consequences; ±90 indicate rare, transformative effects. Use the full scale sparingly and proportionally. There is an important distinction between 0 and N/A. Use 0 if the goal of the bill was to provide impact in the policy area - but you are predicting that it will have none. Use N/A if the goals of the bill do not align at all with the policy area. Respond with only the integer and an optional sign, no commentary or units.
			
			{issuesList}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 > r >= 40, C: 40 > r >= 20, D: 20 > r > 0, F: r <= 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			Short Report:
			Your audience is the general public, written at the high-school education level. A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and its expected impact to society. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.
			
			{searchReferences}
			""";
	
	public static final String aggregatePrompt = """
			A large U.S. bill has been split into sections and summarized. Your role is to be a non-partisan oversight committee, evaluating whether or not the following bill will produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).
			
			{searchModelInstructions1}
			
			Neutral Summary:
			Read the bill text in its entirety and write a strictly neutral, non-partisan summary. This section must contain only officially verifiable information drawn from the bill text itself or widely accepted, non-partisan reference sources. The summary should be between one and three paragraphs, depending on the complexity of the bill. Start by describing what the bill does and the mechanisms it uses to achieve its stated objectives, including relevant thresholds, authorities, timelines, or structural changes where necessary. Avoid evaluative or predictive language. If relevant, briefly describe the stated rationale of supporters and the stated concerns of opponents, presenting both perspectives factually and symmetrically, without implying which is stronger or more correct. Your tone must remain strictly neutral and descriptive. Do not take sides, imply outcomes, assess impacts, or suggest whether the policy is good or bad. This section should be analogous in style and purpose to Congress.gov’s “Official Summary” and should be suitable for citation by journalists as reference-grade material. You may include links to neutral, non-partisan sources (for example, Congress.gov or Wikipedia) using markdown link syntax where appropriate. Do not include formatting symbols such as stars or dashes (excluding links). Do not include non-human-readable text such as XML identifiers, internal IDs, or metadata.
			
			Bill Title:
			Write the bill title. If the bill does not have a title and is only referred to by its bill number (such as HR 4141), please make up a very short title for the bill based on its content.
			
			Structural Analysis:
			Your goal in this section is to evaluate the bill across seven core pillars. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the pillar number, name and a colon, exactly as written here, followed by your analysis. Conclude each pillar analysis by writing either exactly "<PASS>", or "<FAIL>", denoting that the bill has either passed or failed that pillar of the structural analysis.
			
			1. Precision:
			Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?
			2. Evidence:
			Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?
			3. Feasibility:
			Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints?
			4. Budget:
			Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations?
			5. Fairness:
			How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups?
			6. Governance:
			Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse?
			7. Risk:
			Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes?
			
			Long Report:
			Your audience is political enthusiasts and/or professionals, written at a college education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving an analysis on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. If acronyms are referenced, which are not common knowledge, please define them.  Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Confidence:
			A self-rated integer from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 > r >= 40, C: 40 > r >= 20, D: 20 > r > 0, F: r <= 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			Casual Report:
			Your audience is the general public, written at the high-school education level. Your primary goal here is to take the insights from the long report and make them available for your average person. Begin by explaining the high level goals of the bill and a high level summary of how it attempts to achieve those goals. Then, explain all the essential logic required to understand your predicted impact to society, including any research, expert opinions, societal wisdom and/or any competing concerns which may exist. Conclude with your findings on the impact the bill will have on society. Should be between one and three paragraphs long, depending on the complexity of the bill and the topics it covers (controversial topics require more evidence to support your conclusions). Do not use acronyms, such as GPO, CBO, etc. If you must use them, they must be defined. You may link to sources using markdown link syntax, where relevant. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Short Report:
			Your audience is the general public, written at the high-school education level. A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and its expected impact to society. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.
			
			{searchReferences}
			""";
	
	public static final String statsPrompt;
	public static final String slicePrompt;
	static {
		String issues = String.join("\n", Arrays.stream(TrackedIssue.values()).map(issue -> issue.getName() + ": <score or N/A>").toList());
    	statsPrompt = statsPromptTemplate.replaceFirst("\\{issuesList\\}", issues);
    	slicePrompt = slicePromptTemplate.replaceFirst("\\{issuesList\\}", issues);
	}
	
	public static final String SEARCH_MODEL_INSTRUCTIONS1 = """
Supplemental Web Search:
This is your first section, and your goal is to query the web and gather additional supplemental information, which may or may not exist. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the step number, name and a colon, exactly as written here, followed by your search results. If you do not have access to the web, output 'Unable to access the web' and end this section; NEVER make up fake sources. Information for each of these sections may or may not exist; if it cannot be found, it must not be treated as evidence for or against the bill.

1. Advocate / Sponsor Reasoning:
Gather any information which may have been published on the web from the bill's sponsor or likely advocates. We're looking here for rationale which might explain their goals and reasoning process.
2. Opposition Reasoning:
Gather any information which may have been published on the web from the bill's opposition. We're looking here for rationale which might explain their goals and reasoning process.
3. Budget Report:
Search to see if a budgetary analysis by a credible organization has been published for this bill. If this is a congressional bill, search for a CBO report. if it's a state legislature then find the equivalent organization (if one exists). If a credible budgetary analysis was found, write the findings here.
4. Media Analysis:
Search for existing analysis which may have been published by a news organization. Be careful to avoid introducing partisan bias into the analysis in this process.
5. Identify Broader Context:
Attempt to identify the broader context in which this bill exists, and any larger strategy or context it may be a part of. For example sometimes a bill can be a response to sub-optimal choices made elsewhere.
			""";
	public static final String SEARCH_MODEL_INSTRUCTIONS2 = " Where appropriate, please cite references from your search inside the report. References can be cited using markdown link syntax: [explanation text here](http://example.com)";
	public static final String SEARCH_REFERENCES = """
Search References:
Output the header (Search References:) and exactly one line that is valid JSON and nothing else.

Required JSON shape - The output must be a JSON array. Each element must be a JSON array of exactly 8 strings, in this exact order:
[url, author, title, authors_opinion_int_string, authors_opinion_text, summary, long_summary, reference_type]

1. url: full URL string
2. author: author name or "Unknown"
3. title: article/page title
4. authors_opinion_int_string: a string integer from "-100" to "100" representing the author's opinion on the bill, either positive (in favor) or negative (against).
5. authors_opinion_text: one of: "N/A" | "Mixed" | "Neutral" | "For" | "Against". Again, represents the author's opinion, either for or against, the bill itself.
6. summary: 1–2 sentence concise summary of why this reference mattered
7. long_summary: must contain all critical information from the article that was actually used in the analysis (include key findings, numbers, constraints, definitions, conclusions, and any caveats that materially affected reasoning). It should be detailed enough that a reader can understand exactly what evidence was used without opening the link.
8. reference_type: one of: "STAKEHOLDER" | "BUDGETARY" | "NEWS" | "ACADEMIC" | "LEGAL" | "GOVERNMENT" | "OTHER"

Hard rules (to ensure machine readability)
1. Output must be valid JSON per JSON.parse(...).
2. Output must be a JSON array, not an object.
3. Do not wrap the JSON in quotes. Never output “stringified JSON.”
  Bad: "[["https://..."]]"
  Bad: ["[\"https://...\"]"]
  Good: [[ "https://...", ... ]]
4. Do not include markdown, code fences, labels, commentary, or extra whitespace lines.
5. Use only standard JSON double quotes " (no smart quotes).
6. No trailing commas.
7. Do not include newline characters outside JSON string escaping. (If newlines are needed inside long_summary, encode them as \n.)
8. If no references were used, output exactly: []
9. Each top-level element must be a JSON array, not a string

Content rule (important)
Only include references that were actually used as evidence in the analysis (not links that were merely found but not used). If an item is mentioned in the analysis, it must appear here; if it is not used, it must not appear here.

Example (format reference only)
[["https://pmc.ncbi.nlm.nih.gov/articles/PMC9677302/","Sajjad et al., Canadian Journal of Kidney Health and Disease, 2022","Motivators and Barriers to Living Donor Kidney Transplant as Perceived by Past and Potential Donors","0","N/A","Peer-reviewed study citing lack of job security as a significant barrier to living donation.","A large proportion of women and men reported that guaranteed job security (47% women and 38% of men), paid time off (51% of women and 42% of men), reimbursement of lost wages (49% of women and 38% of men), and protections to guarantee no impact on future insurability (62% of women and 52% of men) were significant motivators to donate.","ACADEMIC"]]
 
 Before printing the final line, internally verify the output is a JSON array of arrays, each inner array has length 8, and every element is a string; if not, correct it until it passes.
			""";
	
	public static String getPromptForBill(boolean isAggregate, boolean searchEnabled) {
		String prompt;
		
		if (isAggregate) {
			prompt = aggregatePrompt;
		} else {
			prompt = statsPrompt;
		}
		
		if (searchEnabled) {
			prompt = prompt.replaceFirst("\\{searchModelInstructions1\\}", SEARCH_MODEL_INSTRUCTIONS1);
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", SEARCH_MODEL_INSTRUCTIONS2);
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", SEARCH_MODEL_INSTRUCTIONS2);
			prompt = prompt.replaceFirst("\\{searchReferences\\}", SEARCH_REFERENCES);
		} else {
			prompt = prompt.replaceFirst("\\{searchModelInstructions1\\}", "");
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", "");
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", "");
			prompt = prompt.replaceFirst("\\{searchModelStep\\}", "");
			prompt = prompt.replaceFirst("\\{searchReferences\\}", "");
		}
		
		return prompt;
	}
	
}
