use std::str::FromStr;

use iroh_tickets::endpoint::EndpointTicket;

use crate::error::PatchpipeError;

#[derive(Debug, uniffi::Record)]
pub struct TicketInfo {
    pub endpoint_id: String,
    pub relay_urls: Vec<String>,
}

pub fn parse_endpoint_ticket(ticket: &str) -> Result<EndpointTicket, PatchpipeError> {
    let trimmed = ticket.trim();
    EndpointTicket::from_str(trimmed)
        .map_err(|e| PatchpipeError::message(format!("invalid ticket: {e}")))
}

pub fn ticket_info(ticket: &EndpointTicket) -> TicketInfo {
    let addr = ticket.endpoint_addr();
    TicketInfo {
        endpoint_id: addr.id.to_string(),
        relay_urls: addr.relay_urls().map(|u| u.to_string()).collect(),
    }
}

pub fn describe_ticket(ticket: &str) -> Result<TicketInfo, PatchpipeError> {
    Ok(ticket_info(&parse_endpoint_ticket(ticket)?))
}
