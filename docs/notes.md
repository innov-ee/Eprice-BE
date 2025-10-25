# Domain
Every day theres auction for next days prices that closes 12:00 CET, results are published ca 13:00 CET.

## Entso-E
Max future prices are +1 day. Further than that would be speculation which Entso-e does not provide.
It offers historic prices, required to provide -5 years, but practically since ~2015.

# Entso-E API
API details at: https://documenter.getpostman.com/view/7009892/2s93JtP3F6#3b383df0-ada2-49fe-9a50-98b1bb201c6b
Commented sample response: https://gitlab.entsoe.eu/transparency/xml-examples/-/blob/main/Market/Energy%20Prices%20%5B12.1.D%5D%20-%20XSD7:3.xml

XML schema definitions: https://gitlab.entsoe.eu/transparency/xsd

PriceDocument model hints:
page 21 of: "MoP Ref8_Transmission Transparency Process_v4r12.pdf"  (search A44)
from https://www.entsoe.eu/data/transparency-platform/mop/

## Reliability
Note that Points can have gaps (next position is not guaranteed i+1)