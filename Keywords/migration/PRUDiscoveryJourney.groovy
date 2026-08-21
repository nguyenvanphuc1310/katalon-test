package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil

/**
 * Katalon Custom Keywords still generate migration.PRUDiscoveryJourney.run(Map).
 * Each DS case now lives in PRUDiscoveryCases; this class only delegates.
 */
public class PRUDiscoveryJourney {

	@Keyword
	def run(Map vars) {
		String id = (vars?.testId ?: '').toString().trim()
		KeywordUtil.logInfo('PRUDiscoveryJourney.run ' + id)
		switch (id) {
			case 'DS-001': PRUDiscoveryCases.ds001(); break
			case 'DS-002': PRUDiscoveryCases.ds002(); break
			case 'DS-003': PRUDiscoveryCases.ds003(); break
			case 'DS-004': PRUDiscoveryCases.ds004(); break
			case 'DS-005': PRUDiscoveryCases.ds005(); break
			case 'DS-006': PRUDiscoveryCases.ds006(); break
			case 'DS-007': PRUDiscoveryCases.ds007(); break
			case 'DS-008': PRUDiscoveryCases.ds008(); break
			case 'DS-009': PRUDiscoveryCases.ds009(); break
			case 'DS-010': PRUDiscoveryCases.ds010(); break
			case 'DS-011': PRUDiscoveryCases.ds011(); break
			case 'DS-012': PRUDiscoveryCases.ds012(); break
			case 'DS-013': PRUDiscoveryCases.ds013(); break
			case 'DS-014': PRUDiscoveryCases.ds014(); break
			case 'DS-015': PRUDiscoveryCases.ds015(); break
			default:
				KeywordUtil.logInfo('Unknown testId "' + id + '". Use TC_DS_001 … TC_DS_015.')
		}
		return [status: 'OK', testId: id]
	}
}
